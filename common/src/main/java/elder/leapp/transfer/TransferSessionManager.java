package elder.leapp.transfer;

// TransferSessionManager.java
// Owns all session state and lifecycle for the transfer system.
// Extracted from TransferOrchestrator as part of TO1 split.
//
// Responsibilities:
//   - Active session map (playerUuid → TransferSession)
//   - Pending player UUID set (players in the pre-session probe phase)
//   - Cooldown map (playerUuid → last successful transfer timestamp)
//   - Timeout scheduler — polls active sessions every second
//   - Session creation, discard, and failure handling
//   - Portal trigger cleanup (LeapPortalBlock.clearTrigger)
//   - Scheduler shutdown on world stop
//
// All methods are package-private or called only by TransferOrchestrator
// and TransferSequencer. Nothing outside the transfer package should
// interact with this class directly.

import elder.leapp.LeapPadCommon;
import elder.leapp.portal.LeapPortalBlock;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TransferSessionManager {

    // -------------------------------------------------------
    // Timeout values (milliseconds) — from Architecture Plan Part 7
    // -------------------------------------------------------
    static final long TIMEOUT_PROBE_MS     = 5_000;
    static final long TIMEOUT_PROFILE_MS   = 15_000;
    static final long TIMEOUT_HOST_PREP_MS = 5_000;
    static final long TIMEOUT_READY_MS     = 5_000;

    // -------------------------------------------------------
    // Session state
    // -------------------------------------------------------

    // One session per player currently in a transfer sequence.
    // Key: player UUID string.
    static final Map<String, TransferSession> activeSessions =
        new ConcurrentHashMap<>();

    // Players in the pre-session probe phase — before a session exists.
    // Prevents a second connection attempt starting while the probe is in flight.
    // SS2 fix: cleared in a try-finally block in TransferSequencer.runProbe()
    // so uncaught exceptions cannot leak a UUID here permanently.
    static final Set<String> pendingPlayerUuids =
        ConcurrentHashMap.newKeySet();

    // Cooldown map — playerUuid → timestamp of last successful transfer.
    // SS3 fix: cleared on SERVER_STOPPING via shutdown().
    static final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------
    // Timeout scheduler
    // -------------------------------------------------------

    // Single background thread that polls active sessions every second.
    // SS4 fix: shut down via shutdown() on SERVER_STOPPING.
    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeapPad-TimeoutWatcher");
            t.setDaemon(true);
            return t;
        });

    static {
        scheduler.scheduleAtFixedRate(
            TransferSessionManager::checkTimeouts, 1, 1, TimeUnit.SECONDS
        );
    }

    // -------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------

    // Called on SERVER_STARTING.
    // SS1 fix: clears stale sessions from any previous world load in the same JVM.
    public static void init() {
        activeSessions.clear();
        pendingPlayerUuids.clear();
        LeapPadCommon.LOGGER.info("[Leap! Pad] TransferSessionManager initialised.");
    }

    // Called on SERVER_STOPPING.
    // SS3: clears cooldown entries — all cooldowns are meaningless after world stop.
    // SS4: shuts down the timeout scheduler cleanly.
    public static void shutdown() {
        cooldownMap.clear();
        scheduler.shutdownNow();
        LeapPadCommon.LOGGER.info("[Leap! Pad] TransferSessionManager shut down.");
    }

    // -------------------------------------------------------
    // Session access
    // -------------------------------------------------------

    static TransferSession getSession(String playerUuid) {
        return activeSessions.get(playerUuid);
    }

    static void putSession(String playerUuid, TransferSession session) {
        activeSessions.put(playerUuid, session);
    }

    static boolean hasActiveSession(String playerUuid) {
        return activeSessions.containsKey(playerUuid) ||
               pendingPlayerUuids.contains(playerUuid);
    }

    // -------------------------------------------------------
    // Session discard and failure
    // -------------------------------------------------------

    // Removes a session from all tracking maps and clears the portal trigger
    // if this was a portal-path session.
    static void discardSession(String playerUuid, TransferSession session) {
        activeSessions.remove(playerUuid);
        pendingPlayerUuids.remove(playerUuid);
        if (session != null && session.isPortalPath) {
            clearPortalTrigger(playerUuid);
        }
    }

    static void failSession(String playerUuid, TransferSession session,
                             String step, String reason,
                             TransferOrchestrator.PlayerNotifier playerNotifier) {
        LeapPadCommon.LOGGER.error(
            "[Leap! Pad] Failed at {} for {}: {}", step, playerUuid, reason
        );
        session.advanceTo(TransferSession.TransferState.FAILED);
        if (playerNotifier != null) playerNotifier.showTimeout(playerUuid);
        discardSession(playerUuid, session);
    }

    // -------------------------------------------------------
    // Cooldown
    // -------------------------------------------------------

    static void recordCooldown(String playerUuid) {
        cooldownMap.put(playerUuid, System.currentTimeMillis());
    }

    public static boolean isOnCooldown(String playerUuid) {
        Long last = cooldownMap.get(playerUuid);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) <
               (elder.leapp.config.LeapPadConfig.portalCooldownSeconds * 1000L);
    }

    // -------------------------------------------------------
    // Timeout polling
    // -------------------------------------------------------

    private static void checkTimeouts() {
        // Notifier reference captured from orchestrator for failure notification
        TransferOrchestrator.PlayerNotifier notifier =
            TransferOrchestrator.getPlayerNotifier();

        for (Map.Entry<String, TransferSession> entry : activeSessions.entrySet()) {
            String playerUuid       = entry.getKey();
            TransferSession session = entry.getValue();

            // Never time out COMPLETE sessions — waiting for mixin confirmation
            if (session.state == TransferSession.TransferState.COMPLETE) continue;

            long elapsed = session.millisInCurrentState();

            boolean timedOut = switch (session.state) {
                case PROBING, AWAITING_GATE ->
                    elapsed > TIMEOUT_PROBE_MS;
                case AWAITING_PROFILE, SENDING_DAT ->
                    elapsed > TIMEOUT_PROFILE_MS;
                case AWAITING_HOST_PREP, AWAITING_UUID_LIST,
                     DECONFLICTING, SENDING_UUID ->
                    elapsed > TIMEOUT_HOST_PREP_MS;
                case AWAITING_READY_ECHO ->
                    elapsed > TIMEOUT_READY_MS;
                default -> false;
            };

            if (timedOut) {
                failSession(playerUuid, session,
                    "Step " + session.state.name(),
                    "Timed out after " + elapsed + "ms in state " + session.state,
                    notifier
                );
            }
        }
    }

    // -------------------------------------------------------
    // Portal trigger cleanup
    // -------------------------------------------------------

    // Clears the re-entry guard in LeapPortalBlock so the player can use portals again.
    static void clearPortalTrigger(String playerUuid) {
        try {
            LeapPortalBlock.clearTrigger(UUID.fromString(playerUuid));
        } catch (IllegalArgumentException e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Could not clear portal trigger for {}: bad UUID format.", playerUuid
            );
        }
    }
}
