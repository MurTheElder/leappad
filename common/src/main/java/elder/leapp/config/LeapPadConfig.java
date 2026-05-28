package elder.leapp.config;

// LeapPadConfig.java
// Reads leappad.json from the world's config folder on world start.
// Every value is validated strictly — nothing silently falls back to a default.
// Any invalid value causes immediate world startup failure with a clear error
// message naming the key, the value provided, and what was expected.
// All values are accessible as static fields throughout the mod after load.

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import elder.leapp.LeapPadCommon;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LeapPadConfig {

    // -------------------------------------------------------
    // Static fields — read these from anywhere after init()
    // -------------------------------------------------------

    // Primary probe port offset. Probe port = game port + this value.
    public static int probePortOffset;

    // Fallback probe port offsets tried in order when the primary gets no Leap! Pad response.
    public static int[] probePortFallbacks;

    // Per-player cooldown in seconds after portal arrival before the portal fires again.
    public static int portalCooldownSeconds;

    // Maximum radius in blocks the mirror portal placement algorithm searches.
    public static int maxPlacementSearchRadius;

    // Blocks treated as clearance for portal placement column checks.
    public static String[] nonObstructiveBlocks;

    // Valid portal frame materials — populates the leappad:frame_blocks tag at runtime.
    public static String[] frameBlocks;

    // n — total autosave cycles for all buckets to complete one full push rotation.
    public static int playerDatPushTime;

    // p — number of buckets to divide players into.
    public static int playerDatCyclic;

    // Number of characters used as the active UUID for portal identification.
    public static int portalDesignationActiveLength;

    // -------------------------------------------------------
    // Internals
    // -------------------------------------------------------

    private static final Gson GSON = new Gson();

    // Default nonObstructiveBlocks list — finalized during Phase 4 playtesting.
    // Contains blocks that should be treated as empty space for portal placement.
    private static final String[] DEFAULT_NON_OBSTRUCTIVE = {
        "minecraft:air",
        "minecraft:cave_air",
        "minecraft:void_air",
        "minecraft:water",
        "minecraft:lava",
        "minecraft:grass",
        "minecraft:tall_grass",
        "minecraft:fern",
        "minecraft:large_fern",
        "minecraft:dead_bush",
        "minecraft:vine",
        "minecraft:snow",
        "minecraft:torch",
        "minecraft:wall_torch",
        "minecraft:soul_torch",
        "minecraft:soul_wall_torch",
        "minecraft:lantern",
        "minecraft:soul_lantern",
        "leappad:leap_portal"
    };

    // Called on world start. Loads or creates leappad.json and validates all values.
    // Throws IllegalStateException on any validation failure — this stops the world from starting.
    public static void load(Path configDir) {
        Path configFile = configDir.resolve("leappad.json");

        JsonObject json;

        if (Files.exists(configFile)) {
            // File exists — read and parse it
            try (Reader reader = Files.newBufferedReader(configFile)) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException e) {
                throw new IllegalStateException(
                    "[Leap! Pad] Failed to read leappad.json: " + e.getMessage(), e
                );
            }
        } else {
            // File does not exist — write a default config and use it
            LeapPadCommon.LOGGER.info("[Leap! Pad] No leappad.json found — writing default config.");
            json = buildDefaults();
            try {
                Files.createDirectories(configDir);
                try (Writer writer = Files.newBufferedWriter(configFile)) {
                    new Gson().toJson(json, writer);
                }
            } catch (IOException e) {
                throw new IllegalStateException(
                    "[Leap! Pad] Failed to write default leappad.json: " + e.getMessage(), e
                );
            }
        }

        // Validate and assign every field
        probePortOffset           = requirePositiveInt(json, "probePortOffset");
        probePortFallbacks        = requirePositiveIntArray(json, "probePortFallbacks");
        portalCooldownSeconds     = requirePositiveInt(json, "portalCooldownSeconds");
        maxPlacementSearchRadius  = requirePositiveInt(json, "maxPlacementSearchRadius");
        nonObstructiveBlocks      = requireNonEmptyStringArray(json, "nonObstructiveBlocks");
        frameBlocks               = requireNonEmptyStringArray(json, "frameBlocks");
        playerDatPushTime         = requirePositiveInt(json, "playerDatPushTime");
        playerDatCyclic           = requirePositiveInt(json, "playerDatCyclic");
        portalDesignationActiveLength = requirePositiveInt(json, "portalDesignationActiveLength");

        // playerDatPushTime must be a multiple of playerDatCyclic
        if (playerDatPushTime % playerDatCyclic != 0) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: 'playerDatPushTime' (" + playerDatPushTime + ")" +
                " must be a multiple of 'playerDatCyclic' (" + playerDatCyclic + ")."
            );
        }

        LeapPadCommon.LOGGER.info("[Leap! Pad] Config loaded successfully.");
    }

    // -------------------------------------------------------
    // Validation helpers — each one fails hard with a clear message
    // -------------------------------------------------------

    private static int requirePositiveInt(JsonObject json, String key) {
        if (!json.has(key)) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: missing required key '" + key + "'."
            );
        }
        JsonElement el = json.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: '" + key + "' must be an integer, got: " + el
            );
        }
        int value = el.getAsInt();
        if (value <= 0) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: '" + key + "' must be a positive integer, got: " + value
            );
        }
        return value;
    }

    private static int[] requirePositiveIntArray(JsonObject json, String key) {
        if (!json.has(key)) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: missing required key '" + key + "'."
            );
        }
        JsonElement el = json.get(key);
        if (!el.isJsonArray() || el.getAsJsonArray().size() == 0) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: '" + key + "' must be a non-empty array of positive integers."
            );
        }
        int[] result = new int[el.getAsJsonArray().size()];
        int i = 0;
        for (JsonElement entry : el.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) {
                throw new IllegalStateException(
                    "[Leap! Pad] Config error: all values in '" + key + "' must be integers, found: " + entry
                );
            }
            int v = entry.getAsInt();
            if (v <= 0) {
                throw new IllegalStateException(
                    "[Leap! Pad] Config error: all values in '" + key + "' must be positive integers, found: " + v
                );
            }
            result[i++] = v;
        }
        return result;
    }

    private static String[] requireNonEmptyStringArray(JsonObject json, String key) {
        if (!json.has(key)) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: missing required key '" + key + "'."
            );
        }
        JsonElement el = json.get(key);
        if (!el.isJsonArray() || el.getAsJsonArray().size() == 0) {
            throw new IllegalStateException(
                "[Leap! Pad] Config error: '" + key + "' must be a non-empty array of strings."
            );
        }
        List<String> result = new ArrayList<>();
        for (JsonElement entry : el.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException(
                    "[Leap! Pad] Config error: all values in '" + key + "' must be strings, found: " + entry
                );
            }
            result.add(entry.getAsString());
        }
        return result.toArray(new String[0]);
    }

    // -------------------------------------------------------
    // Default config builder
    // -------------------------------------------------------

    private static JsonObject buildDefaults() {
        JsonObject obj = new JsonObject();
        obj.addProperty("probePortOffset", 1);

        com.google.gson.JsonArray fallbacks = new com.google.gson.JsonArray();
        for (int f : new int[]{37, 41, 43, 47, 53}) fallbacks.add(f);
        obj.add("probePortFallbacks", fallbacks);

        obj.addProperty("portalCooldownSeconds", 15);
        obj.addProperty("maxPlacementSearchRadius", 16);

        com.google.gson.JsonArray nonObstr = new com.google.gson.JsonArray();
        for (String s : DEFAULT_NON_OBSTRUCTIVE) nonObstr.add(s);
        obj.add("nonObstructiveBlocks", nonObstr);

        com.google.gson.JsonArray frames = new com.google.gson.JsonArray();
        for (String s : new String[]{
            "minecraft:prismarine",
            "minecraft:prismarine_bricks",
            "minecraft:dark_prismarine"
        }) frames.add(s);
        obj.add("frameBlocks", frames);

        obj.addProperty("playerDatPushTime", 1);
        obj.addProperty("playerDatCyclic", 1);
        obj.addProperty("portalDesignationActiveLength", 5);

        return obj;
    }
}
