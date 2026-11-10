package net.hearthigen.hardcoretracker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Reads quest progress from Vanilla Progression: Wilderness Bound.
 * Wilderness Bound counts completed quests (0 to 300) per player or team, so the
 * current age and quest number are worked out from that count.
 * It is read through reflection so this mod still loads if Wilderness Bound is missing or changes.
 */
public final class Progression {
	/** Quests in each of the nine ages, in order. */
	public static final int[] AGE_QUESTS = {28, 26, 24, 41, 34, 30, 44, 24, 49};
	/** The website's point bracket for each age (1-based). */
	private static final String[] POINT_KEYS = {"wood", "wood", "copper", "iron", "iron", "diamond", "diamond", "end", "end"};
	private static final int TOTAL = 300;

	private Progression() {}

	/** Completed quest count for this player's team, or the server's best if the player has none. -1 if unknown. */
	public static int completedFor(MinecraftServer server, UUID player) {
		int own = -1;
		try {
			Object ledger = liveLedger(server);
			if (ledger != null) {
				Method progress = ledger.getClass().getMethod("progress", UUID.class);
				Object state = progress.invoke(ledger, player);
				if (state != null) own = intField(state, "completed");
			}
		} catch (Throwable t) {
			HardcoreTracker.LOG.debug("[HardcoreTracker] Couldn't read player progress live", t);
		}
		if (own > 0) return own;
		int best = serverBest(server);
		return Math.max(own, best);
	}

	/** Highest completed quest count among everyone on the server. -1 if unknown. */
	public static int serverBest(MinecraftServer server) {
		try {
			Object ledger = liveLedger(server);
			if (ledger != null) {
				Field playersField = ledger.getClass().getField("players");
				Map<?, ?> players = (Map<?, ?>) playersField.get(ledger);
				int best = -1;
				for (Object state : players.values()) best = Math.max(best, intField(state, "completed"));
				if (best >= 0) return best;
			}
		} catch (Throwable t) {
			HardcoreTracker.LOG.debug("[HardcoreTracker] Couldn't read progress live, trying the save file", t);
		}
		return fromSaveFile(server);
	}

	private static Object liveLedger(MinecraftServer server) throws Exception {
		Class<?> questServer;
		try {
			questServer = Class.forName("com.studiousgoose.wilderness.progression.QuestServer");
		} catch (ClassNotFoundException e) {
			return null;
		}
		Object instance = questServer.getMethod("get", MinecraftServer.class).invoke(null, server);
		if (instance == null) return null;
		Object available = questServer.getMethod("available").invoke(instance);
		if (available instanceof Boolean b && !b) return null;
		return questServer.getMethod("ledger").invoke(instance);
	}

	private static int fromSaveFile(MinecraftServer server) {
		try {
			Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("wilderness-bound-progress.json");
			if (!Files.exists(file)) return -1;
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!root.has("players")) return -1;
			int best = -1;
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("players").entrySet()) {
				JsonObject p = e.getValue().getAsJsonObject();
				if (p.has("completed")) best = Math.max(best, p.get("completed").getAsInt());
			}
			return best;
		} catch (Exception e) {
			HardcoreTracker.LOG.debug("[HardcoreTracker] Couldn't read the Wilderness Bound save file", e);
			return -1;
		}
	}

	private static int intField(Object obj, String name) throws Exception {
		Field f = obj.getClass().getField(name);
		return f.getInt(obj);
	}

	/** Age number 1-9 for a completed quest count. */
	public static int age(int completed) {
		int c = Math.max(0, Math.min(completed, TOTAL - 1));
		int start = 0;
		for (int i = 0; i < AGE_QUESTS.length; i++) {
			if (c < start + AGE_QUESTS[i]) return i + 1;
			start += AGE_QUESTS[i];
		}
		return AGE_QUESTS.length;
	}

	/** Current quest number inside its age (1-based) for a completed quest count. */
	public static int questInAge(int completed) {
		int c = Math.max(0, Math.min(completed, TOTAL - 1));
		int start = 0;
		for (int count : AGE_QUESTS) {
			if (c < start + count) return c - start + 1;
			start += count;
		}
		return AGE_QUESTS[AGE_QUESTS.length - 1];
	}

	/** The website's point key ("wood", "copper", "iron", "diamond", "end") for an age number 1-9. */
	public static String pointKey(int age) {
		return POINT_KEYS[Math.max(1, Math.min(age, 9)) - 1];
	}

	public static int points(String key) {
		return switch (key) {
			case "copper" -> 2;
			case "iron" -> 3;
			case "diamond" -> 4;
			case "end" -> 5;
			default -> 1;
		};
	}
}
