package elder.leapp.config;

// WorldLanConfig.java
// Per-world config for LAN auto-open. Stored at:
//   [world save]/leappad/leappad_lan.json
//
// A single field: lanPort (int).
// isConfigured() returns true only if lanPort > 0.
// Values of 0 (unset) and -1 (explicitly closed) both mean disabled.
//
// This config is separate from leappad.json (which is global) because
// the LAN port is a per-world setting — different worlds may want
// different ports or no auto-open at all.

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import elder.leapp.LeapPadCommon;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class WorldLanConfig {

    // The port to automatically open this world to LAN on.
    // 0 = unset (disabled), -1 = explicitly closed (disabled), >0 = enabled.
    public int lanPort = 0;

    // Optional override for the minimum OP level required to use /portalid registry showip.
    // -1 means not set — fall back to the global leappad.json value.
    // When present (1–4), this world's value takes priority over the global config.
    // Must be added manually to leappad_lan.json — not present in the default file.
    private int ipVisibilityMinOpLevel = -1;

    // Staged LAN port value from the Create World screen.
    // Set by CreateWorldScreenMixin as the player types, consumed and cleared
    // in LeapPadFabric.SERVER_STARTING after the world save path is available.
    // -1 means not set.
    private static int stagedLanPort = -1;

    public static void setStagedLanPort(int port) { stagedLanPort = port; }
    public static int  getStagedLanPort()          { return stagedLanPort; }
    public static void clearStagedLanPort()        { stagedLanPort = -1; }

    // Returns true if this world has an explicit ipVisibilityMinOpLevel override.
    public boolean hasIpVisibilityOverride() {
        return ipVisibilityMinOpLevel >= 1 && ipVisibilityMinOpLevel <= 4;
    }

    // Returns this world's ipVisibilityMinOpLevel override, or -1 if not set.
    // Callers should check hasIpVisibilityOverride() before using this value.
    public int getIpVisibilityMinOpLevel() {
        return ipVisibilityMinOpLevel;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "leappad_lan.json";

    // Returns true only if auto-open is configured and enabled.
    public boolean isConfigured() {
        return lanPort > 0;
    }

    // Loads the config from [worldSaveDir]/leappad/leappad_lan.json.
    // Returns an unconfigured instance (lanPort=0) if the file does not exist
    // or cannot be read — graceful degradation, no crash.
    public static WorldLanConfig load(Path worldSaveDir) {
        Path file = worldSaveDir.resolve("leappad").resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new WorldLanConfig();
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            WorldLanConfig config = new WorldLanConfig();
            if (json.has("lanPort")) {
                config.lanPort = json.get("lanPort").getAsInt();
            }
            if (json.has("ipVisibilityMinOpLevel")) {
                config.ipVisibilityMinOpLevel = json.get("ipVisibilityMinOpLevel").getAsInt();
            }
            return config;
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Could not read leappad_lan.json: {} — using defaults.", e.getMessage()
            );
            return new WorldLanConfig();
        }
    }

    // Saves the config to [worldSaveDir]/leappad/leappad_lan.json.
    // Creates the leappad/ directory if it doesn't exist.
    public void save(Path worldSaveDir) {
        Path dir  = worldSaveDir.resolve("leappad");
        Path file = dir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dir);
            JsonObject json = new JsonObject();
            json.addProperty("lanPort", lanPort);
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Could not write leappad_lan.json: {}", e.getMessage()
            );
        }
    }
}
