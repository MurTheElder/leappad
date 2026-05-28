package elder.leapp.profile;

// AutosavePushManager.java
// Hooks Minecraft's autosave event and distributes player dat pushes across time
// using a bucket system so no single autosave cycle bears the full load.
//
// Concepts:
//   n (playerDatPushTime)  — total autosave cycles for all buckets to rotate once
//   p (playerDatCyclic)    — number of buckets
//   One bucket is pushed every n/p cycles
//   Each player's dat is pushed once every n cycles
//
// Bucket assignment (on join via ServerPlayConnectionEvents.JOIN):
//   Player goes to the bucket with the lowest current occupancy
//   Tiebreak: lowest bucket index wins
//
// Push always happens regardless of active profile status on the client —
// the client decides whether to save the received dat.

import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

public class AutosavePushManager {

    // The buckets — each bucket holds a list of players currently assigned to it
    private static List<List<ServerPlayer>> buckets;

    // How many autosave cycles have elapsed since world start
    private static int cycleCount = 0;

    // Index of the bucket that will be pushed next
    private static int nextBucketIndex = 0;

    // Interface for actually sending the dat push packet to a player.
    // Implemented in FabricNetworking and injected at startup.
    public interface DatPusher {
        void pushDat(ServerPlayer player);
    }

    private static DatPusher datPusher;

    public static void setDatPusher(DatPusher pusher) {
        datPusher = pusher;
    }

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    // Called once on world start to initialize the bucket array.
    // Config validation already guarantees p > 0 and n is a multiple of p.
    public static void init() {
        int p = LeapPadConfig.playerDatCyclic;
        buckets = new ArrayList<>(p);
        for (int i = 0; i < p; i++) {
            buckets.add(new ArrayList<>());
        }
        cycleCount = 0;
        nextBucketIndex = 0;
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] AutosavePushManager initialized with {} bucket(s).", p
        );
    }

    // -------------------------------------------------------
    // Player join / leave
    // -------------------------------------------------------

    // Assigns the joining player to the bucket with the lowest current count.
    // Called from ServerPlayConnectionEvents.JOIN in LeapPadFabric.
    public static void assignBucket(ServerPlayer player) {
        if (buckets == null) init();

        int targetBucket = 0;
        int lowestCount = Integer.MAX_VALUE;

        for (int i = 0; i < buckets.size(); i++) {
            int count = buckets.get(i).size();
            if (count < lowestCount) {
                lowestCount = count;
                targetBucket = i;
            }
        }

        buckets.get(targetBucket).add(player);
        LeapPadCommon.LOGGER.debug(
            "[Leap! Pad] Player {} assigned to bucket {}.",
            player.getName().getString(), targetBucket
        );
    }

    // Immediately pushes the leaving player's dat, then removes them from their bucket.
    // Called from ServerPlayConnectionEvents.DISCONNECT in LeapPadFabric.
    public static void onPlayerLeave(ServerPlayer player) {
        pushPlayer(player);

        if (buckets == null) return;
        for (List<ServerPlayer> bucket : buckets) {
            bucket.remove(player);
        }
    }

    // -------------------------------------------------------
    // Autosave hook
    // -------------------------------------------------------

    // Called every time Minecraft's autosave fires (every 6000 ticks / 5 minutes by default).
    // Increments the cycle counter and pushes the next non-empty bucket when due.
    public static void onAutosave() {
        if (buckets == null) return;

        cycleCount++;
        int n = LeapPadConfig.playerDatPushTime;
        int p = LeapPadConfig.playerDatCyclic;
        int pushInterval = n / p; // How many cycles between each bucket push

        if (cycleCount % pushInterval != 0) return; // Not time to push yet

        // Find the next non-empty bucket starting from nextBucketIndex
        int searched = 0;
        while (searched < p) {
            int idx = nextBucketIndex % p;
            nextBucketIndex++;
            searched++;

            List<ServerPlayer> bucket = buckets.get(idx);
            if (!bucket.isEmpty()) {
                LeapPadCommon.LOGGER.debug(
                    "[Leap! Pad] Autosave push — bucket {} ({} player(s)).",
                    idx, bucket.size()
                );
                // Push a snapshot of the bucket list (avoid ConcurrentModificationException
                // if a player leaves mid-push)
                for (ServerPlayer player : new ArrayList<>(bucket)) {
                    pushPlayer(player);
                }
                return;
            }
        }
        // All buckets empty — nothing to push this cycle
    }

    // -------------------------------------------------------
    // Graceful shutdown
    // -------------------------------------------------------

    // Pushes all currently connected players regardless of bucket or cycle position.
    // Called on world close to ensure everyone's dat is current before the world shuts down.
    public static void onShutdown() {
        if (buckets == null) return;
        LeapPadCommon.LOGGER.info("[Leap! Pad] Shutdown — pushing dat for all connected players.");
        for (List<ServerPlayer> bucket : buckets) {
            for (ServerPlayer player : new ArrayList<>(bucket)) {
                pushPlayer(player);
            }
        }
    }

    // -------------------------------------------------------
    // Internal push
    // -------------------------------------------------------

    // Sends the current player dat to the given player via the dat pusher.
    private static void pushPlayer(ServerPlayer player) {
        if (datPusher == null) {
            LeapPadCommon.LOGGER.warn("[Leap! Pad] DatPusher not set — cannot push dat for {}.",
                player.getName().getString());
            return;
        }
        try {
            datPusher.pushDat(player);
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Failed to push dat for {}: {}",
                player.getName().getString(), e.getMessage()
            );
        }
    }
}
