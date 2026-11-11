package net.hearthigen.hardcoretracker;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/** Watches for player deaths and quest progress, and sends them to the website. */
public final class HardcoreTracker implements ModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("hardcoretracker");

	private TrackerConfig config;
	private WebsiteSync sync;
	private long lastDeathAt = 0;
	private int lastSentCompleted = -2;
	private int ticks = 0;

	@Override
	public void onInitialize() {
		config = TrackerConfig.load();
		sync = new WebsiteSync(config);

		if (!config.enabled) {
			LOG.info("[HardcoreTracker] Disabled in config/hardcoretracker.json.");
		} else if (!config.ready()) {
			LOG.warn("[HardcoreTracker] No GitHub token yet. Add githubToken to config/hardcoretracker.json and restart. Deaths will be saved and sent once it's set.");
		} else {
			LOG.info("[HardcoreTracker] Ready. Sending updates to {}.", config.repository);
		}

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			sync.kick();
			checkProgress(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			checkProgress(server);
			sync.flushAndStop(10);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) onDeath(player, source);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!config.syncQuestProgress) return;
			if (++ticks >= Math.max(1, config.questSyncMinutes) * 1200) {
				ticks = 0;
				checkProgress(server);
			}
		});
	}

	private void onDeath(ServerPlayer player, DamageSource source) {
		if (!config.enabled) return;
		try {
			long now = System.currentTimeMillis();
			String name = player.getName().getString();
			if (now - lastDeathAt < Math.max(0, config.ignoreDeathsWithinSeconds) * 1000L) {
				LOG.info("[HardcoreTracker] Not logging {}'s death; it came right after another one (shared life).", name);
				return;
			}
			lastDeathAt = now;

			WebsiteSync.Job job = new WebsiteSync.Job();
			job.type = "death";
			job.player = name;
			job.playerUuid = player.getUUID().toString();
			job.cause = cause(player, source, name);
			job.time = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
			MinecraftServer server = player.level().getServer();
			job.completed = server != null ? Progression.completedFor(server, player.getUUID()) : -1;
			if (job.completed >= 0) lastSentCompleted = job.completed;

			LOG.info("[HardcoreTracker] Logging death: {} - {}", name, job.cause);
			sync.submit(job);
		} catch (Throwable t) {
			LOG.error("[HardcoreTracker] Something went wrong logging a death", t);
		}
	}

	private void checkProgress(MinecraftServer server) {
		if (!config.enabled || !config.syncQuestProgress) return;
		try {
			int completed = Progression.serverBest(server);
			if (completed < 0 || completed == lastSentCompleted) return;
			lastSentCompleted = completed;
			WebsiteSync.Job job = new WebsiteSync.Job();
			job.type = "progress";
			job.completed = completed;
			sync.submit(job);
		} catch (Throwable t) {
			LOG.debug("[HardcoreTracker] Couldn't check quest progress", t);
		}
	}

	/** Turns "SubKiwi was shot by Skeleton" into "Shot by Skeleton", matching the website's death log. */
	static String cause(ServerPlayer player, DamageSource source, String name) {
		// By the time this runs the combat tracker may already be cleared (it then just says "died"),
		// so ask the damage source first; it still knows who or what did it.
		String best = null;
		try {
			best = clean(source.getLocalizedDeathMessage(player).getString(), name);
		} catch (Throwable ignored) {
		}
		if (best == null || best.equalsIgnoreCase("died")) {
			try {
				Component msg = player.getCombatTracker().getDeathMessage();
				String tracked = msg != null ? clean(msg.getString(), name) : null;
				if (tracked != null && (best == null || !tracked.equalsIgnoreCase("died"))) best = tracked;
			} catch (Throwable ignored) {
			}
		}
		if (best == null || best.isEmpty()) return "Died";
		return Character.toUpperCase(best.charAt(0)) + best.substring(1);
	}

	private static String clean(String text, String name) {
		if (text == null) return null;
		text = text.trim();
		if (text.startsWith(name)) text = text.substring(name.length()).trim();
		if (text.startsWith("was ")) text = text.substring(4);
		return text.isEmpty() ? null : text;
	}

}
