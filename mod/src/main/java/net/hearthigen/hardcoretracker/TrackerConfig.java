package net.hearthigen.hardcoretracker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings stored in config/hardcoretracker.json on the server. */
public final class TrackerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Set to false to pause all website updates without removing the mod. */
	public boolean enabled = true;
	/** Fine-grained GitHub token with "Contents: Read and write" on the website repository. Keep this secret. */
	public String githubToken = "";
	/** The website repository, as owner/name. */
	public String repository = "HardcoreTracker/hardcoretracker.github.io";
	public String branch = "main";
	public String dataPath = "data.json";
	/**
	 * After a death is logged, deaths in the next this-many seconds are ignored.
	 * Shared life kills everyone at once, and only the first death should count.
	 */
	public int ignoreDeathsWithinSeconds = 30;
	/** Also keep the website's current age and quest in sync with Wilderness Bound. */
	public boolean syncQuestProgress = true;
	/** How often to check quest progress, in minutes. */
	public int questSyncMinutes = 5;

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("hardcoretracker.json");
	}

	public static TrackerConfig load() {
		Path path = file();
		TrackerConfig config = null;
		try {
			if (Files.exists(path)) {
				config = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), TrackerConfig.class);
			}
		} catch (Exception e) {
			HardcoreTracker.LOG.error("[HardcoreTracker] Couldn't read {}. Fix the file or delete it to get a fresh one.", path, e);
			config = new TrackerConfig();
			config.enabled = false;
			return config;
		}
		if (config == null) config = new TrackerConfig();
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HardcoreTracker.LOG.warn("[HardcoreTracker] Couldn't write {}", path, e);
		}
		return config;
	}

	public boolean ready() {
		return enabled && githubToken != null && !githubToken.isBlank() && repository != null && repository.contains("/");
	}
}
