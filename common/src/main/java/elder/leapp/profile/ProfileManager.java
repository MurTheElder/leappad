package elder.leapp.profile;

// ProfileManager.java
// Loads, saves, lists, and deletes character profiles from disk.
// One .nbt file per profile, stored at .minecraft/leappad/profiles/[UUID].nbt
//
// Also manages:
//   - The active profile string (cleared on title screen render, with one-cycle suppression)
//   - Dat blob I/O — writing the blob to playerdata/[UUID].dat on join,
//     capturing received dat on push from host
//   - Leap! Forward presence flag (set once at launch by the caller)
//   - Cached external IP (fetched async by FabricCommandRegistry, stored here for reuse)
//
// Client-side only. Called from LeapPadFabricClient and TransferOrchestrator.
//
// S4 fix: Removed FabricLoader calls from this class. init() now takes profilesDir
// and leapForwardPresent as parameters, supplied by LeapPadFabricClient. This keeps
// common code free of loader-specific imports and makes ProfileManager reusable
// for the NeoForge edition without modification.
//
// S5 note: NbtIo File-based read/write calls are deprecated in 1.20.1 but are the
// only available overloads at this Minecraft version. Path-based and NbtAccounter-based
// overloads do not exist until later versions. The deprecation warning is harmless.

import elder.leapp.LeapPadCommon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ProfileManager {

    // Root directory for all profile files: .minecraft/leappad/profiles/
    private static Path profilesDir;

    // All loaded profiles, keyed by profile UUID
    private static final Map<String, CharacterProfile> profiles = new LinkedHashMap<>();

    // The UUID of the currently active profile — empty if none selected
    private static String activeProfileUuid = "";

    // Whether Leap! Forward is installed on this client.
    // Set by the caller (LeapPadFabricClient) at launch via init().
    private static boolean leapForwardPresent = false;

    // Cached external IP — fetched async by FabricCommandRegistry, stored here for reuse
    // by TransferOrchestrator when building probe packets.
    private static String cachedExternalIp = "";

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    // Called from LeapPadFabricClient.onInitializeClient().
    // S4 fix: profilesDir and leapForwardPresent are now passed in by the caller
    // rather than resolved here via FabricLoader. This removes the Fabric API
    // dependency from common code.
    public static void init(Path profilesDir, boolean leapForwardPresent) {
        ProfileManager.profilesDir = profilesDir;
        ProfileManager.leapForwardPresent = leapForwardPresent;

        try {
            Files.createDirectories(profilesDir);
        } catch (IOException e) {
            LeapPadCommon.LOGGER.error("[Leap! Pad] Could not create profiles directory: {}", e.getMessage());
            return;
        }

        loadAllFromDisk();

        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] ProfileManager initialized. {} profile(s) loaded. Leap! Forward: {}",
            profiles.size(), leapForwardPresent
        );
    }

    // -------------------------------------------------------
    // Profile CRUD
    // -------------------------------------------------------

    // Returns all profiles as an unmodifiable list, sorted by last used time descending
    public static List<CharacterProfile> getAllProfiles() {
        List<CharacterProfile> list = new ArrayList<>(profiles.values());
        list.sort((a, b) -> Long.compare(b.lastUsedTime, a.lastUsedTime));
        return Collections.unmodifiableList(list);
    }

    // Creates a new profile with the given display name and saves it to disk.
    // Returns the new profile.
    public static CharacterProfile createProfile(String displayName) {
        String uuid = UUID.randomUUID().toString();
        CharacterProfile profile = new CharacterProfile(uuid, displayName);
        profiles.put(uuid, profile);
        saveToDisk(profile);
        LeapPadCommon.LOGGER.info("[Leap! Pad] Profile created: {} ({})", displayName, uuid);
        return profile;
    }

    // Deletes a profile by UUID. Removes from disk and memory.
    public static void deleteProfile(String profileUuid) {
        profiles.remove(profileUuid);
        if (profilesDir != null) {
            Path file = profilesDir.resolve(profileUuid + ".nbt");
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LeapPadCommon.LOGGER.warn("[Leap! Pad] Could not delete profile file {}: {}", file, e.getMessage());
            }
        }
        // Clear active profile if the deleted one was active
        if (profileUuid.equals(activeProfileUuid)) {
            activeProfileUuid = "";
        }
    }

    // Returns the profile for the given UUID, or null if not found
    public static CharacterProfile getProfile(String profileUuid) {
        return profiles.get(profileUuid);
    }

    // -------------------------------------------------------
    // Active profile management
    // -------------------------------------------------------

    public static void setActiveProfile(String profileUuid) {
        activeProfileUuid = profileUuid == null ? "" : profileUuid;
    }

    public static String getActiveProfileUuid() {
        return activeProfileUuid;
    }

    // Returns the dat blob for the currently active profile, or null if none
    public static byte[] getActiveDatBlob() {
        if (activeProfileUuid.isEmpty()) return null;
        CharacterProfile profile = profiles.get(activeProfileUuid);
        return profile == null ? null : profile.datBlob;
    }

    // Clears the active profile string.
    // Called by TitleScreenMixin on every title screen render,
    // except when the suppression flag is set (one cycle after selector closes).
    public static void clearActiveProfile() {
        activeProfileUuid = "";
    }

    // -------------------------------------------------------
    // Dat blob handling — called when host pushes player dat back to client
    // -------------------------------------------------------

    // Saves the received dat blob to the active profile.
    // Called when a leappad:profile_dat_push packet arrives from the host.
    public static void saveReceivedDat(byte[] datBlob) {
        if (activeProfileUuid.isEmpty()) return; // No active profile — discard

        CharacterProfile profile = profiles.get(activeProfileUuid);
        if (profile == null) return;

        profile.datBlob = datBlob;
        profile.lastUsedTime = System.currentTimeMillis();
        saveToDisk(profile);

        LeapPadCommon.LOGGER.debug(
            "[Leap! Pad] Dat blob saved to profile {} ({} bytes)",
            activeProfileUuid, datBlob.length
        );
    }

    // Updates the last used address for the active profile.
    // Called when a connection completes successfully.
    public static void updateLastUsedAddress(String address) {
        if (activeProfileUuid.isEmpty()) return;
        CharacterProfile profile = profiles.get(activeProfileUuid);
        if (profile == null) return;
        profile.lastUsedAddress = address;
        profile.lastUsedTime = System.currentTimeMillis();
        saveToDisk(profile);
    }

    // Returns the UUID of the last profile used for the given world address,
    // if that profile still exists. Used by the profile selector to pre-select.
    public static String getLastUsedProfileForAddress(String address) {
        CharacterProfile best = null;
        for (CharacterProfile p : profiles.values()) {
            if (address.equals(p.lastUsedAddress)) {
                if (best == null || p.lastUsedTime > best.lastUsedTime) {
                    best = p;
                }
            }
        }
        return best == null ? null : best.profileUuid;
    }

    // -------------------------------------------------------
    // Leap! Forward flag
    // -------------------------------------------------------

    public static boolean isLeapForwardPresent() {
        return leapForwardPresent;
    }

    // -------------------------------------------------------
    // External IP cache
    // -------------------------------------------------------

    public static String getCachedExternalIp() {
        return cachedExternalIp;
    }

    public static void setCachedExternalIp(String ip) {
        cachedExternalIp = ip;
    }

    // -------------------------------------------------------
    // Disk I/O
    // -------------------------------------------------------

    private static void loadAllFromDisk() {
        profiles.clear();
        if (profilesDir == null || !Files.exists(profilesDir)) return;

        try (var stream = Files.newDirectoryStream(profilesDir, "*.nbt")) {
            for (Path file : stream) {
                try {
                    // NbtIo.read(File) is deprecated in 1.20.1 but correct for this version.
                    CompoundTag tag = NbtIo.read(file.toFile());
                    if (tag == null) continue;
                    CharacterProfile profile = CharacterProfile.fromNbt(tag);
                    profiles.put(profile.profileUuid, profile);
                } catch (IOException e) {
                    LeapPadCommon.LOGGER.warn(
                        "[Leap! Pad] Could not load profile file {}: {}", file.getFileName(), e.getMessage()
                    );
                }
            }
        } catch (IOException e) {
            LeapPadCommon.LOGGER.error("[Leap! Pad] Could not read profiles directory: {}", e.getMessage());
        }
    }

    private static void saveToDisk(CharacterProfile profile) {
        if (profilesDir == null) return;
        Path file = profilesDir.resolve(profile.profileUuid + ".nbt");
        try {
            // NbtIo.write(CompoundTag, File) is deprecated in 1.20.1 but correct for this version.
            NbtIo.write(profile.toNbt(), file.toFile());
        } catch (IOException e) {
            LeapPadCommon.LOGGER.error(
                "[Leap! Pad] Could not save profile {}: {}", profile.profileUuid, e.getMessage()
            );
        }
    }
}
