package net.hearthigen.hardcoretracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends updates to the website by editing data.json in its GitHub repository.
 * Updates wait in a queue saved to disk, so nothing is lost if GitHub is down or the server stops.
 */
public final class WebsiteSync {
	private static final Gson SAVE = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

	/** One pending change. type is "death" or "progress". */
	public static final class Job {
		public String type;
		public String player;
		public String playerUuid;
		public String cause;
		public String time;
		public int completed = -1;
	}

	private final TrackerConfig config;
	private final Path queueFile = FabricLoader.getInstance().getConfigDir().resolve("hardcoretracker-queue.json");
	private final List<Job> queue = new ArrayList<>();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "HardcoreTracker-Sync");
		t.setDaemon(true);
		return t;
	});
	private final ScheduledExecutorService retry = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "HardcoreTracker-Retry");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean retryScheduled = false;

	public WebsiteSync(TrackerConfig config) {
		this.config = config;
		loadQueue();
	}

	public synchronized int pending() {
		return queue.size();
	}

	public void submit(Job job) {
		synchronized (this) {
			if ("progress".equals(job.type)) queue.removeIf(j -> "progress".equals(j.type));
			queue.add(job);
			saveQueue();
		}
		kick();
	}

	public void kick() {
		if (!config.ready()) return;
		worker.execute(this::drain);
	}

	/** Called when the server stops: try to send everything, but don't hold the shutdown up for long. */
	public void flushAndStop(long timeoutSeconds) {
		if (config.ready() && pending() > 0) {
			try {
				worker.submit(this::drain).get(timeoutSeconds, TimeUnit.SECONDS);
			} catch (Exception e) {
				HardcoreTracker.LOG.warn("[HardcoreTracker] Stopped before every update was sent. {} will be sent next time the server starts.", pending());
			}
		}
		worker.shutdownNow();
		retry.shutdownNow();
	}

	private void drain() {
		while (true) {
			Job job;
			synchronized (this) {
				if (queue.isEmpty()) return;
				job = queue.get(0);
			}
			boolean done;
			try {
				done = apply(job);
			} catch (Exception e) {
				HardcoreTracker.LOG.warn("[HardcoreTracker] Couldn't update the website yet ({}). Will retry in a minute.", e.getMessage());
				done = false;
			}
			if (!done) {
				scheduleRetry();
				return;
			}
			synchronized (this) {
				queue.remove(job);
				saveQueue();
			}
		}
	}

	private void scheduleRetry() {
		if (retryScheduled) return;
		retryScheduled = true;
		retry.schedule(() -> {
			retryScheduled = false;
			kick();
		}, 60, TimeUnit.SECONDS);
	}

	/** Applies one job to data.json. Returns true once it is saved on GitHub (or there was nothing to change). */
	private boolean apply(Job job) throws Exception {
		for (int attempt = 0; attempt < 3; attempt++) {
			String api = "https://api.github.com/repos/" + config.repository + "/contents/" + config.dataPath;
			HttpResponse<String> get = http.send(request(api + "?ref=" + URLEncoder.encode(config.branch, StandardCharsets.UTF_8)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (get.statusCode() == 401 || get.statusCode() == 403 || get.statusCode() == 404) {
				HardcoreTracker.LOG.error("[HardcoreTracker] GitHub refused access (HTTP {}). Check githubToken and repository in config/hardcoretracker.json.", get.statusCode());
				return false;
			}
			if (get.statusCode() != 200) throw new IllegalStateException("GitHub returned HTTP " + get.statusCode() + " reading " + config.dataPath);

			JsonObject file = JsonParser.parseString(get.body()).getAsJsonObject();
			String sha = file.get("sha").getAsString();
			String text = new String(Base64.getMimeDecoder().decode(file.get("content").getAsString()), StandardCharsets.UTF_8);
			JsonObject data = JsonParser.parseString(text).getAsJsonObject();

			String message = "death".equals(job.type) ? applyDeath(data, job) : applyProgress(data, job);
			if (message == null) return true;

			JsonObject body = new JsonObject();
			body.addProperty("message", message);
			body.addProperty("content", Base64.getEncoder().encodeToString((PRETTY.toJson(data) + "\n").getBytes(StandardCharsets.UTF_8)));
			body.addProperty("sha", sha);
			body.addProperty("branch", config.branch);
			HttpResponse<String> put = http.send(request(api).PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
				HttpResponse.BodyHandlers.ofString());
			if (put.statusCode() == 200 || put.statusCode() == 201) {
				HardcoreTracker.LOG.info("[HardcoreTracker] Website updated: {}", message);
				return true;
			}
			if (put.statusCode() == 409 || put.statusCode() == 422) continue; // someone else edited the file at the same time; reload and retry
			if (put.statusCode() == 401 || put.statusCode() == 403) {
				HardcoreTracker.LOG.error("[HardcoreTracker] GitHub refused the update (HTTP {}). The token needs \"Contents: Read and write\" on {}.", put.statusCode(), config.repository);
				return false;
			}
			throw new IllegalStateException("GitHub returned HTTP " + put.statusCode() + " saving " + config.dataPath);
		}
		return false;
	}

	private HttpRequest.Builder request(String url) {
		return HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(20))
			.header("Authorization", "Bearer " + config.githubToken.trim())
			.header("Accept", "application/vnd.github+json")
			.header("X-GitHub-Api-Version", "2022-11-28")
			.header("User-Agent", "HardcoreTracker-Mod");
	}

	private static String applyDeath(JsonObject data, Job job) {
		JsonObject progress = data.has("progress") ? data.getAsJsonObject("progress") : new JsonObject();
		int age = job.completed >= 0 ? Progression.age(job.completed) : (progress.has("age") ? progress.get("age").getAsInt() : 1);
		String key = Progression.pointKey(age);
		boolean totem = progress.has("totemFarm") && progress.get("totemFarm").getAsBoolean();
		int points = Progression.points(key) + (totem ? 1 : 0);

		String id = findOrAddPlayer(data, job.player);
		OffsetDateTime when = OffsetDateTime.parse(job.time);

		JsonArray deaths = data.has("deaths") ? data.getAsJsonArray("deaths") : new JsonArray();
		String deathId = "m" + when.toInstant().toEpochMilli();
		for (JsonElement e : deaths) {
			if (e.isJsonObject() && deathId.equals(stringOr(e.getAsJsonObject(), "id", ""))) return null; // already logged
		}
		JsonObject death = new JsonObject();
		death.addProperty("id", deathId);
		death.addProperty("player", id);
		death.addProperty("age", key);
		death.addProperty("totem", totem);
		death.addProperty("cause", job.cause);
		death.addProperty("reason", "");
		death.addProperty("date", when.toLocalDate().toString());
		death.addProperty("time", job.time);
		death.addProperty("source", "mod");
		deaths.add(death);
		data.add("deaths", deaths);

		JsonObject last = new JsonObject();
		last.addProperty("player", id);
		last.addProperty("cause", job.cause);
		last.addProperty("time", job.time);
		data.add("lastDeath", last);

		if (job.completed >= 0) setProgress(data, job.completed);
		data.addProperty("updated", when.format(UPDATED));
		return "Death: " + job.player + " - " + job.cause + " (+" + points + ")";
	}

	private static String applyProgress(JsonObject data, Job job) {
		if (job.completed < 0) return null;
		if (!setProgress(data, job.completed)) return null;
		data.addProperty("updated", OffsetDateTime.now().format(UPDATED));
		return "Quest progress: Age " + Progression.age(job.completed) + ", quest " + Progression.questInAge(job.completed);
	}

	/** Returns true if the age or quest changed. Keeps the manual totem farm setting. */
	private static boolean setProgress(JsonObject data, int completed) {
		JsonObject progress = data.has("progress") ? data.getAsJsonObject("progress") : new JsonObject();
		int age = Progression.age(completed), quest = Progression.questInAge(completed);
		boolean changed = !progress.has("age") || progress.get("age").getAsInt() != age
			|| !progress.has("quest") || progress.get("quest").getAsInt() != quest;
		progress.addProperty("age", age);
		progress.addProperty("quest", quest);
		if (!progress.has("totemFarm")) progress.addProperty("totemFarm", false);
		data.add("progress", progress);
		return changed;
	}

	private static String findOrAddPlayer(JsonObject data, String name) {
		JsonArray players = data.has("players") ? data.getAsJsonArray("players") : new JsonArray();
		for (JsonElement e : players) {
			JsonObject p = e.getAsJsonObject();
			if (name.equalsIgnoreCase(stringOr(p, "name", "")) || name.equalsIgnoreCase(stringOr(p, "id", ""))) {
				return stringOr(p, "id", name.toLowerCase(Locale.ROOT));
			}
		}
		String id = name.toLowerCase(Locale.ROOT);
		JsonObject p = new JsonObject();
		p.addProperty("id", id);
		p.addProperty("name", name);
		p.addProperty("nick", name);
		p.addProperty("base", 0);
		p.addProperty("skin", "");
		players.add(p);
		data.add("players", players);
		HardcoreTracker.LOG.info("[HardcoreTracker] {} wasn't on the website yet, so they were added without a skin.", name);
		return id;
	}

	private static String stringOr(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}

	private void loadQueue() {
		try {
			if (Files.exists(queueFile)) {
				List<Job> saved = SAVE.fromJson(Files.readString(queueFile, StandardCharsets.UTF_8), new TypeToken<List<Job>>() {}.getType());
				if (saved != null) queue.addAll(saved);
			}
		} catch (Exception e) {
			HardcoreTracker.LOG.warn("[HardcoreTracker] Couldn't read the update queue; starting empty.", e);
		}
	}

	private void saveQueue() {
		try {
			Files.createDirectories(queueFile.getParent());
			Files.writeString(queueFile, SAVE.toJson(queue), StandardCharsets.UTF_8);
		} catch (Exception e) {
			HardcoreTracker.LOG.warn("[HardcoreTracker] Couldn't save the update queue.", e);
		}
	}
}
