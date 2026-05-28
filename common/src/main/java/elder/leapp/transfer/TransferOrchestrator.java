package elder.leapp.transfer;

// TransferOrchestrator.java
// The main coordinator for all connection attempts.
// Receives handoff from ConnectScreenMixin and drives the full pre-connection sequence.
// Owns transfer key validation, per-player cooldown tracking, and the four timeout timers.
//
// Any timeout logs the failing step to latest.log and discards the session.
// The gate never releases on timeout — the player stays on their current screen.
//
// On the portal path, also coordinates with MirrorPortalManager and PortalRegistry
// for arrival work on the host side.
//
// C3 fix: PortalConnectTrigger interface added alongside existing bridge interfaces.
//         All four bridge interfaces (PacketSender, GateReleaser, PlayerNotifier,
//         PortalConnectTrigger) are injected from LeapPadFabricClient at startup.
//
// S2 fix: triggerPortalConnect() added — called by LeapPortalBlock instead of
//         onConnectionAttempt() directly. Delegates to PortalConnectTrigger bridge.
//
// S6 fix: Session no longer added to activeSessions with a null transfer key.
//         A pendingPlayerUuids set tracks players in the probe phase. The session
//         is only added to activeSessions once the transfer key is known (probe success).
//         failSession() and discardSession() also clean up pendingPlayerUuids.

import elder.leapp.LeapPadCommon;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.profile.ProfileManager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TransferOrchestrator {

    // -------------------------------------------------------
    // Timeout values (milliseconds) — from Architecture Plan Part 7
    // -------------------------------------------------------
    private static final long TIMEOUT_PROBE_MS     = 5_000;  // Steps 3-4
    private static final long TIMEOUT_PROFILE_MS   = 15_000; // Steps 5-6
    private static final long TIMEOUT_HOST_PREP_MS = 5_000;  // Steps 7-10
    private static final long TIMEOUT_READY_MS     = 5_000;  // Steps 11-12

    // -------------------------------------------------------
    // Active sessions — one per player attempting a transfer
    // Key: player UUID string
    // Only populated once the transfer key is known (after probe success).
    // -------------------------------------------------------
    private static final Map<String, TransferSession> activeSessions = new ConcurrentHashMap<>();

    // S6 fix: Tracks players currently in the probe phase, before a session exists.
    // Prevents a second connection attempt starting while the probe is in flight.
    // Entries are added at the start of onConnectionAttempt() and removed when
    // the session is promoted to activeSessions, or when the probe fails/times out.
    private static final Set<String> pendingPlayerUuids =
        ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------
    // Per-player cooldown tracking
    // Key: player UUID string, Value: timestamp of last portal arrival
    // -------------------------------------------------------
    private static final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    // Scheduler for running timeout checks
    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeapPad-TimeoutWatcher");
            t.setDaemon(true);
            return t;
        });

    static {
        // Check for timed-out sessions every second
        scheduler.scheduleAtFixedRate(
            TransferOrchestrator::checkTimeouts, 1, 1, TimeUnit.SECONDS
        );
    }

    // -------------------------------------------------------
    // Entry point — called by ConnectScreenMixin
    // -------------------------------------------------------

    // Called when any join is initiated (portal, direct connect, server list, LAN).
    // playerUuid — the UUID of the player attempting to connect
    // targetAddress — the host:port string being connected to
    // originPortalUuid — the UUID of the portal walked through; null if not a portal path
    // originAddress — this world's address; null if not a portal path
    public static void onConnectionAttempt(String playerUuid,
                                           String targetAddress,
                                           String originPortalUuid,
                                           String originAddress) {

        // Don't start a new session if one is already active or probing for this player
        if (activeSessions.containsKey(playerUuid) || pendingPlayerUuids.contains(playerUuid)) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Transfer already in progress for player {}, ignoring new attempt.", playerUuid
            );
            return;
        }

        boolean isPortalPath = (originPortalUuid != null);
        boolean leapForward  = ProfileManager.isLeapForwardPresent();

        // S6 fix: Register the player as pending before starting the probe.
        // No TransferSession is created yet — the transfer key is not known until
        // the probe succeeds. The session is constructed inside runProbe().
        pendingPlayerUuids.add(playerUuid);

        // Capture values for the probe thread lambda
        final String capturedTarget       = targetAddress;
        final String capturedOriginUuid   = originPortalUuid;
        final String capturedOriginAddr   = originAddress;
        final boolean capturedLf          = leapForward;
        final boolean capturedPortalPath  = isPortalPath;

        // Run the probe on a background thread — never block the game thread
        Thread probeThread = new Thread(
            () -> runProbe(playerUuid, capturedTarget, capturedOriginUuid,
                           capturedOriginAddr, capturedPortalPath, capturedLf),
            "LeapPad-Probe"
        );
        probeThread.setDaemon(true);
        probeThread.start();
    }

    // -------------------------------------------------------
    // Portal connect trigger — called by LeapPortalBlock (S2 fix)
    // -------------------------------------------------------

    // Called by LeapPortalBlock.entityInside() instead of onConnectionAttempt() directly.
    // Delegates to the PortalConnectTrigger bridge, which stores portal context in
    // ConnectScreenMixin and triggers a vanilla connect on the client thread.
    // The mixin then intercepts that connect and calls onConnectionAttempt() with
    // the portal context already in place.
    public static void triggerPortalConnect(String targetAddress,
                                            String portalUuid,
                                            String originAddress) {
        if (portalConnectTrigger != null) {
            portalConnectTrigger.triggerConnect(targetAddress, portalUuid, originAddress);
        } else {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] triggerPortalConnect called but PortalConnectTrigger is not injected."
            );
        }
    }

    // -------------------------------------------------------
    // Step 3 — probe
    // -------------------------------------------------------

    private static void runProbe(String playerUuid,
                                  String targetAddress,
                                  String originPortalUuid,
                                  String originAddress,
                                  boolean isPortalPath,
                                  boolean leapForward) {
        String leapForwardFlag = leapForward ? "present" : "absent";
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Probing {} for player {} (Leap! Forward: {})",
            targetAddress, playerUuid, leapForwardFlag
        );

        // Parse host and port from targetAddress (format: "host:port")
        String host;
        int port;
        try {
            int colon = targetAddress.lastIndexOf(':');
            host = targetAddress.substring(0, colon);
            port = Integer.parseInt(targetAddress.substring(colon + 1));
        } catch (Exception e) {
            pendingPlayerUuids.remove(playerUuid);
            LeapPadCommon.LOGGER.error(
                "[Leap! Pad] Transfer failed at Step 3 for player {}: Could not parse target address: {}",
                playerUuid, targetAddress
            );
            notifyPlayerPortalInactive(playerUuid);
            return;
        }

        // Get this client's external IP from the ProfileManager cache.
        // The cache is populated by /leappad ip (async fetch via FabricCommandRegistry).
        String clientIp = ProfileManager.getCachedExternalIp();
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = "unknown";
        }

        WorldPinger.PingOutcome outcome = WorldPinger.probe(
            host, port, clientIp, leapForward
        );

        switch (outcome.result) {
            case UNREACHABLE -> {
                // Gate never releases — player stays on current screen
                LeapPadCommon.LOGGER.info("[Leap! Pad] Target unreachable: {}", targetAddress);
                pendingPlayerUuids.remove(playerUuid);
                notifyPlayerPortalInactive(playerUuid);
            }
            case REACHABLE_NO_LP -> {
                // S6 fix: Promote from pending to active now — transfer key is still
                // null here but the session only needs to hold state for the warning
                // screen decision, after which the player either cancels or proceeds
                // to vanilla join (no Leap! Pad sequence runs).
                // We use a sentinel empty-string transfer key for this path only.
                TransferSession noLpSession = new TransferSession(
                    "",  // no transfer key — non-Leap! Pad target
                    targetAddress, originAddress, isPortalPath,
                    originPortalUuid, leapForward
                );
                noLpSession.advanceTo(TransferSession.TransferState.AWAITING_GATE);
                pendingPlayerUuids.remove(playerUuid);
                activeSessions.put(playerUuid, noLpSession);
                notifyPlayerNoLeapPad(playerUuid, targetAddress);
            }
            case REACHABLE_HAS_LP -> {
                // S6 fix: Transfer key is now known — construct the session here,
                // not before the probe. This is the first point at which a real
                // session with a valid transfer key enters activeSessions.
                TransferSession session = new TransferSession(
                    outcome.transferKey,
                    targetAddress,
                    originAddress,
                    isPortalPath,
                    originPortalUuid,
                    leapForward
                );
                pendingPlayerUuids.remove(playerUuid);
                activeSessions.put(playerUuid, session);

                // If a profile is already selected, skip the selector (step 6 directly)
                String activeProfile = ProfileManager.getActiveProfileUuid();
                if (activeProfile != null && !activeProfile.isEmpty()) {
                    session.advanceTo(TransferSession.TransferState.SENDING_DAT);
                    sendProfileDat(playerUuid, session);
                } else {
                    // Open profile selector (step 5)
                    session.advanceTo(TransferSession.TransferState.AWAITING_PROFILE);
                    openProfileSelector(playerUuid);
                }
            }
        }
    }

    // -------------------------------------------------------
    // Step 6 — send profile dat
    // -------------------------------------------------------

    private static void sendProfileDat(String playerUuid, TransferSession session) {
        String profileUuid = ProfileManager.getActiveProfileUuid();
        byte[] datBlob     = ProfileManager.getActiveDatBlob();

        if (profileUuid == null || datBlob == null) {
            // No profile — proceed without sending dat (host will use vanilla dat)
            session.advanceTo(TransferSession.TransferState.AWAITING_HOST_PREP);
            notifyHostReady(playerUuid, session);
            return;
        }

        // Delegate to FabricNetworking via the PacketSender interface
        if (packetSender != null) {
            packetSender.sendProfileDat(playerUuid, profileUuid, datBlob, session.leapForwardPresent);
        }
        session.advanceTo(TransferSession.TransferState.AWAITING_HOST_PREP);
    }

    // -------------------------------------------------------
    // Steps 8-10 — UUID deconfliction (portal path only)
    // -------------------------------------------------------

    // Called when the host sends its UUID list (step 8)
    public static void onUuidListReceived(String playerUuid, String[] hostUuids) {
        TransferSession session = activeSessions.get(playerUuid);
        if (session == null) return;
        if (!session.isPortalPath) return;

        session.advanceTo(TransferSession.TransferState.DECONFLICTING);

        // Check if our origin portal UUID conflicts with any on the host
        String ourUuid = session.originPortalUuid;
        boolean conflict = false;
        for (String hostUuid : hostUuids) {
            if (hostUuid.equals(ourUuid)) {
                conflict = true;
                break;
            }
        }

        String agreedUuid;
        if (conflict) {
            // Generate a fresh UUID and overwrite our origin portal entry
            agreedUuid = generatePortalUuid();
            PortalRegistry.updatePortalUuid(session.originPortalUuid, agreedUuid);
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] UUID conflict resolved: {} → {}", ourUuid, agreedUuid
            );
        } else {
            agreedUuid = ourUuid;
        }

        session.agreedPortalUuid = agreedUuid;
        session.advanceTo(TransferSession.TransferState.SENDING_UUID);
        if (packetSender != null) packetSender.sendUuidConfirm(playerUuid, agreedUuid);
    }

    // -------------------------------------------------------
    // Steps 11-12 — READY handshake
    // -------------------------------------------------------

    // Called when the host sends the READY echo (step 12)
    public static void onReadyEchoReceived(String playerUuid) {
        TransferSession session = activeSessions.get(playerUuid);
        if (session == null) return;

        session.advanceTo(TransferSession.TransferState.COMPLETE);

        // Release the gate — vanilla join runs now (D1-B)
        if (gateReleaser != null) {
            gateReleaser.releaseGate(playerUuid);
        } else {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] GateReleaser not injected — gate cannot release for player {}.", playerUuid
            );
        }

        // Record cooldown timestamp so the player can't re-use portals immediately
        cooldownMap.put(playerUuid, System.currentTimeMillis());

        discardSession(playerUuid);
        LeapPadCommon.LOGGER.info("[Leap! Pad] Gate released for player {}", playerUuid);
    }

    // -------------------------------------------------------
    // Timeout checking
    // -------------------------------------------------------

    private static void checkTimeouts() {
        for (Map.Entry<String, TransferSession> entry : activeSessions.entrySet()) {
            String playerUuid      = entry.getKey();
            TransferSession session = entry.getValue();
            long elapsed           = session.millisInCurrentState();

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
                    "Timed out after " + elapsed + "ms in state " + session.state
                );
            }
        }
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private static void failSession(String playerUuid, TransferSession session,
                                     String step, String reason) {
        LeapPadCommon.LOGGER.error(
            "[Leap! Pad] Transfer failed at {} for player {}: {}", step, playerUuid, reason
        );
        session.advanceTo(TransferSession.TransferState.FAILED);
        notifyPlayerTimeout(playerUuid);
        discardSession(playerUuid);
    }

    private static void discardSession(String playerUuid) {
        activeSessions.remove(playerUuid);
        pendingPlayerUuids.remove(playerUuid); // S6: clean up in case discard is called early
    }

    // Generates a portal UUID at the configured active length
    private static String generatePortalUuid() {
        String full = UUID.randomUUID().toString().replace("-", "");
        int len = elder.leapp.config.LeapPadConfig.portalDesignationActiveLength;
        if (full.length() >= len) return full.substring(0, len);
        return full + "0".repeat(len - full.length());
    }

    // Checks whether a player is still within their portal cooldown period
    public static boolean isOnCooldown(String playerUuid) {
        Long last = cooldownMap.get(playerUuid);
        if (last == null) return false;
        long elapsed = System.currentTimeMillis() - last;
        return elapsed < (elder.leapp.config.LeapPadConfig.portalCooldownSeconds * 1000L);
    }

    // -------------------------------------------------------
    // Platform bridge interfaces
    // Implemented in the fabric/ subproject and injected at startup from LeapPadFabricClient.
    // Allow common code to trigger client-side UI, packet sends, and vanilla connect
    // without importing Fabric-specific classes directly.
    // -------------------------------------------------------

    public interface PacketSender {
        void sendProfileDat(String playerUuid, String profileUuid, byte[] datBlob, boolean leapForward);
        void sendUuidConfirm(String playerUuid, String agreedUuid);
        void sendReady(String playerUuid, String transferKey);
    }

    public interface GateReleaser {
        // Triggers the vanilla connect for the given player using stored args (D1-B)
        void releaseGate(String playerUuid);
    }

    public interface PlayerNotifier {
        void showPortalInactive(String playerUuid);
        void showNoLeapPad(String playerUuid, String targetAddress);
        void showTimeout(String playerUuid);
        void openProfileSelector(String playerUuid);
        void notifyHostReady(String playerUuid, TransferSession session);
    }

    // C3 / S2: New interface — called by LeapPortalBlock to trigger a portal connect.
    // The implementation in LeapPadFabricClient stores portal context in ConnectScreenMixin
    // and initiates a vanilla connect on the client thread (D2-B).
    public interface PortalConnectTrigger {
        void triggerConnect(String targetAddress, String portalUuid, String originAddress);
    }

    // Injected by LeapPadFabricClient at startup
    private static PacketSender         packetSender;
    private static GateReleaser         gateReleaser;
    private static PlayerNotifier       playerNotifier;
    private static PortalConnectTrigger portalConnectTrigger;

    public static void setPacketSender(PacketSender sender)               { packetSender = sender; }
    public static void setGateReleaser(GateReleaser releaser)             { gateReleaser = releaser; }
    public static void setPlayerNotifier(PlayerNotifier notifier)         { playerNotifier = notifier; }
    public static void setPortalConnectTrigger(PortalConnectTrigger t)    { portalConnectTrigger = t; }

    // Convenience wrappers — null-check before calling notifier
    private static void notifyPlayerPortalInactive(String playerUuid) {
        if (playerNotifier != null) playerNotifier.showPortalInactive(playerUuid);
    }
    private static void notifyPlayerNoLeapPad(String playerUuid, String address) {
        if (playerNotifier != null) playerNotifier.showNoLeapPad(playerUuid, address);
    }
    private static void notifyPlayerTimeout(String playerUuid) {
        if (playerNotifier != null) playerNotifier.showTimeout(playerUuid);
    }
    private static void openProfileSelector(String playerUuid) {
        if (playerNotifier != null) playerNotifier.openProfileSelector(playerUuid);
    }
    private static void notifyHostReady(String playerUuid, TransferSession session) {
        if (playerNotifier != null) playerNotifier.notifyHostReady(playerUuid, session);
    }
}
