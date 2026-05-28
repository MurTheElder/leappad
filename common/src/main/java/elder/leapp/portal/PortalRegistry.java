package elder.leapp.portal;

// PortalRegistry.java
// The persistent portal registry — stored at leappad/portal_registry.dat in the world save.
// Maps UUID → (target address + all corner block coordinates).
// Maintains an in-memory reverse map (BlockPos → UUID) rebuilt on world load.
//
// UUID scheme: each entry has a "general UUID" (full stored string) and an "active UUID"
// (the first portalDesignationActiveLength characters). Commands and identity checks
// always use the active UUID. The suffix is the inactive portion — stored but not used
// until active length increases.
//
// This class handles:
//   - Portal registration at ignition time
//   - Book-throw link writes via AddressParser
//   - UUID deconfliction during handshake
//   - /portalid remove (by coord or by UUID)
//   - World address storage (so LeapPortalBlock can read this world's own address)
//   - Pending mirror portal tracking for UUID finalisation at step 10 (ST4)
//
// S5 note: NbtIo.read(File) and NbtIo.write(CompoundTag, File) are deprecated in
// 1.20.1 but are the only available overloads at this Minecraft version. Path-based
// and NbtAccounter-based overloads do not exist until later versions. The deprecation
// warning is harmless — these calls are correct and safe for 1.20.1.

import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PortalRegistry {

    // One entry per registered portal UUID
    public static class PortalEntry {
        public String generalUuid;      // Full stored UUID string
        public String linkedAddress;    // Target world address this portal connects to (may be empty)
        public String nickname;         // Optional display name set via /portalid nickname
        public boolean isBound;         // True if UUID was set via /portalid bind (exempt from shortening bumps)
        public List<BlockPos> corners;  // All corner coordinates for all frames under this UUID

        public PortalEntry(String generalUuid) {
            this.generalUuid = generalUuid;
            this.linkedAddress = "";
            this.nickname = "";
            this.isBound = false;
            this.corners = new ArrayList<>();
        }
    }

    // UUID → PortalEntry  (source of truth, written to disk)
    private static final Map<String, PortalEntry> registry = new LinkedHashMap<>();

    // BlockPos → active UUID  (in-memory only, rebuilt from registry on world load)
    private static final Map<BlockPos, String> reverseMap = new ConcurrentHashMap<>();

    // playerUuid → mirror portal active UUID
    // Populated by MirrorPortalManager when it places a mirror portal (step 7).
    // Read by updateMirrorPortalUuid() when the agreed UUID arrives (step 10 / ST4).
    // Entries are removed once finalised or on session failure.
    private static final Map<String, String> pendingMirrorPortals = new ConcurrentHashMap<>();

    // This world's own connection address — set by /leappad ip or on world open
    private static String thisWorldAddress = "";

    // Path to portal_registry.dat in the current world save
    private static Path registryPath;

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    // Called on world load. Sets the save path and loads existing data.
    public static void load(Path worldSaveDir) {
        registryPath = worldSaveDir.resolve("leappad").resolve("portal_registry.dat");
        registry.clear();
        reverseMap.clear();
        pendingMirrorPortals.clear();

        if (!Files.exists(registryPath)) {
            LeapPadCommon.LOGGER.info("[Leap! Pad] No portal_registry.dat found — starting fresh.");
            return;
        }

        try {
            // NbtIo.read(File) is deprecated in 1.20.1 but is the correct call for this version.
            // Path-based overloads are not available until later Minecraft versions.
            CompoundTag root = NbtIo.read(registryPath.toFile());
            if (root == null) return;

            ListTag entries = root.getList("portals", Tag.TAG_COMPOUND);
            for (Tag t : entries) {
                CompoundTag entryTag = (CompoundTag) t;
                String uuid = entryTag.getString("uuid");
                PortalEntry entry = new PortalEntry(uuid);
                entry.linkedAddress = entryTag.getString("address");
                entry.nickname = entryTag.getString("nickname");
                entry.isBound = entryTag.getBoolean("bound");

                ListTag cornerTags = entryTag.getList("corners", Tag.TAG_COMPOUND);
                for (Tag ct : cornerTags) {
                    CompoundTag c = (CompoundTag) ct;
                    BlockPos pos = new BlockPos(c.getInt("x"), c.getInt("y"), c.getInt("z"));
                    entry.corners.add(pos);
                    // Rebuild reverse map using the active portion of the UUID
                    reverseMap.put(pos, getActiveUuid(uuid));
                }
                registry.put(getActiveUuid(uuid), entry);
            }

            LeapPadCommon.LOGGER.info("[Leap! Pad] Portal registry loaded — {} portals.", registry.size());
        } catch (IOException e) {
            LeapPadCommon.LOGGER.error("[Leap! Pad] Failed to load portal_registry.dat: {}", e.getMessage());
        }
    }

    // Writes the current registry state to disk.
    // Called after any modification that needs to survive a world restart.
    public static void save() {
        if (registryPath == null) return;
        try {
            Files.createDirectories(registryPath.getParent());
            CompoundTag root = new CompoundTag();
            ListTag entries = new ListTag();

            for (PortalEntry entry : registry.values()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.putString("uuid", entry.generalUuid);
                entryTag.putString("address", entry.linkedAddress);
                entryTag.putString("nickname", entry.nickname);
                entryTag.putBoolean("bound", entry.isBound);

                ListTag cornerTags = new ListTag();
                for (BlockPos pos : entry.corners) {
                    CompoundTag c = new CompoundTag();
                    c.putInt("x", pos.getX());
                    c.putInt("y", pos.getY());
                    c.putInt("z", pos.getZ());
                    cornerTags.add(c);
                }
                entryTag.put("corners", cornerTags);
                entries.add(entryTag);
            }

            root.put("portals", entries);
            // NbtIo.write(CompoundTag, File) is deprecated in 1.20.1 but is the correct call.
            NbtIo.write(root, registryPath.toFile());
        } catch (IOException e) {
            LeapPadCommon.LOGGER.error("[Leap! Pad] Failed to save portal_registry.dat: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------
    // Registration — called at portal ignition
    // -------------------------------------------------------

    // Registers a new portal frame.
    // corners — all corner positions of the frame being lit
    // Returns the UUID assigned to this frame (new or reused from a matching corner).
    public static String registerPortal(List<BlockPos> corners) {
        // Check if any corner matches an existing entry — if so, reuse that UUID
        for (BlockPos incoming : corners) {
            for (Map.Entry<String, PortalEntry> entry : registry.entrySet()) {
                for (BlockPos existing : entry.getValue().corners) {
                    if (existing.equals(incoming)) {
                        // Reuse existing UUID — add any new corners not already stored
                        String activeUuid = entry.getKey();
                        PortalEntry portalEntry = entry.getValue();
                        for (BlockPos c : corners) {
                            if (!portalEntry.corners.contains(c)) {
                                portalEntry.corners.add(c);
                            }
                            reverseMap.put(c, activeUuid);
                        }
                        save();
                        return activeUuid;
                    }
                }
            }
        }

        // No match — generate a new UUID
        String newUuid = generateFreshUuid();
        PortalEntry entry = new PortalEntry(newUuid);
        entry.corners.addAll(corners);
        registry.put(newUuid, entry);
        for (BlockPos c : corners) reverseMap.put(c, newUuid);
        save();
        LeapPadCommon.LOGGER.info("[Leap! Pad] Portal registered with UUID: {}", newUuid);
        return newUuid;
    }

    // -------------------------------------------------------
    // Link management
    // -------------------------------------------------------

    // Links a portal UUID to a target address (called when a book is thrown in).
    // Returns false if the UUID is not registered.
    public static boolean linkPortal(String activeUuid, String address) {
        PortalEntry entry = registry.get(activeUuid);
        if (entry == null) return false;
        entry.linkedAddress = address;
        save();
        return true;
    }

    // Returns the linked address for a given active UUID, or null if not linked.
    public static String getLinkedAddress(String activeUuid) {
        PortalEntry entry = registry.get(activeUuid);
        if (entry == null) return null;
        return entry.linkedAddress.isEmpty() ? null : entry.linkedAddress;
    }

    // -------------------------------------------------------
    // Reverse lookup — used by LeapPortalBlock.entityInside()
    // -------------------------------------------------------

    public static String getUuidForPos(BlockPos pos) {
        return reverseMap.get(pos);
    }

    // -------------------------------------------------------
    // UUID deconfliction — used by TransferOrchestrator step 9
    // -------------------------------------------------------

    // Returns all active UUIDs currently in the registry.
    public static String[] getAllActiveUuids() {
        return registry.keySet().toArray(new String[0]);
    }

    // Replaces an old UUID with a new one across both maps and the entry itself.
    // Called during deconfliction when the client generates a replacement UUID.
    public static void updatePortalUuid(String oldActiveUuid, String newActiveUuid) {
        PortalEntry entry = registry.remove(oldActiveUuid);
        if (entry == null) return;
        entry.generalUuid = newActiveUuid;
        registry.put(newActiveUuid, entry);
        // Update reverse map entries
        for (BlockPos pos : entry.corners) {
            reverseMap.put(pos, newActiveUuid);
        }
        save();
    }

    // -------------------------------------------------------
    // Pending mirror portal tracking — ST4 support
    // -------------------------------------------------------

    // Records that a mirror portal was placed for a connecting player.
    // Called by MirrorPortalManager after it places and registers the portal.
    // The UUID stored here is the provisional one assigned at placement time;
    // it will be replaced with the agreed UUID when updateMirrorPortalUuid() fires.
    public static void registerPendingMirrorPortal(String playerUuid, String provisionalPortalUuid) {
        pendingMirrorPortals.put(playerUuid, provisionalPortalUuid);
    }

    // Finalises a mirror portal's UUID after deconfliction (step 10 / ST4).
    // Replaces the provisional UUID that was assigned at placement with the
    // agreed UUID negotiated between client and host.
    // Called from FabricNetworking when the uuid_confirm packet arrives.
    public static void updateMirrorPortalUuid(String playerUuid, String agreedUuid) {
        String provisionalUuid = pendingMirrorPortals.remove(playerUuid);
        if (provisionalUuid == null) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] updateMirrorPortalUuid called for player {} but no pending portal found.",
                playerUuid
            );
            return;
        }
        // If the provisional and agreed UUIDs are the same, no rename needed
        if (!provisionalUuid.equals(agreedUuid)) {
            updatePortalUuid(provisionalUuid, agreedUuid);
        }
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Mirror portal UUID finalised for player {}: {} → {}",
            playerUuid, provisionalUuid, agreedUuid
        );
    }

    // Clears the pending mirror portal entry for a player.
    // Called on session failure to avoid stale entries building up.
    public static void clearPendingMirrorPortal(String playerUuid) {
        pendingMirrorPortals.remove(playerUuid);
    }

    // -------------------------------------------------------
    // /portalid remove
    // -------------------------------------------------------

    // Removes a specific frame by any one of its corner coordinates.
    // Clears all interior block positions from the reverse map for that frame.
    public static boolean removeByCorner(BlockPos corner) {
        String activeUuid = reverseMap.get(corner);
        if (activeUuid == null) return false;
        PortalEntry entry = registry.remove(activeUuid);
        if (entry == null) return false;
        for (BlockPos c : entry.corners) reverseMap.remove(c);
        save();
        return true;
    }

    // Removes all frames registered under a given UUID.
    public static boolean removeByUuid(String activeUuid) {
        PortalEntry entry = registry.remove(activeUuid);
        if (entry == null) return false;
        for (BlockPos c : entry.corners) reverseMap.remove(c);
        save();
        return true;
    }

    // -------------------------------------------------------
    // Nickname
    // -------------------------------------------------------

    public static boolean setNickname(String activeUuid, String nickname) {
        PortalEntry entry = registry.get(activeUuid);
        if (entry == null) return false;
        entry.nickname = nickname;
        save();
        return true;
    }

    public static String getNickname(String activeUuid) {
        PortalEntry entry = registry.get(activeUuid);
        return entry == null ? null : entry.nickname;
    }

    // -------------------------------------------------------
    // World address
    // -------------------------------------------------------

    public static String getThisWorldAddress() {
        return thisWorldAddress;
    }

    public static void setThisWorldAddress(String address) {
        thisWorldAddress = address;
    }

    // -------------------------------------------------------
    // Read helpers used by commands
    // -------------------------------------------------------

    public static Map<String, PortalEntry> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    public static PortalEntry getEntry(String activeUuid) {
        return registry.get(activeUuid);
    }

    // -------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------

    // Returns the active portion of a UUID (first portalDesignationActiveLength characters).
    private static String getActiveUuid(String generalUuid) {
        int len = LeapPadConfig.portalDesignationActiveLength;
        if (generalUuid.length() <= len) return generalUuid;
        return generalUuid.substring(0, len);
    }

    // Generates a fresh UUID at exactly portalDesignationActiveLength characters.
    // Ensures no collision with existing active UUIDs.
    private static String generateFreshUuid() {
        int len = LeapPadConfig.portalDesignationActiveLength;
        String candidate;
        int attempts = 0;
        do {
            String raw = UUID.randomUUID().toString().replace("-", "");
            // UUIDs use hex characters (0-9, a-f) — trim or pad to active length
            candidate = raw.length() >= len
                ? raw.substring(0, len)
                : raw + "0".repeat(len - raw.length());
            attempts++;
            if (attempts > 1000) {
                // Extremely unlikely — would mean nearly all 16^len slots are taken
                throw new IllegalStateException("[Leap! Pad] Could not generate a unique portal UUID after 1000 attempts.");
            }
        } while (registry.containsKey(candidate));
        return candidate;
    }
}
