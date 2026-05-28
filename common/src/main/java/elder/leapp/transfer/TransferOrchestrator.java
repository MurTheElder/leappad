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

import elder.leapp.LeapPadCommon;
import elder.leapp.portal.MirrorPortalManager;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.profile.ProfileManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TransferOrchestrator {

    // -------------------------------------------------------
    // Timeout values (milliseconds) — from Architecture Plan Part 7
    // -------------------------------------------------------
    private static final long TIMEOUT_PROBE_MS        = 5_000;  // Steps 3-4
    private static final long TIMEOUT_PROFILE_MS      = 15_000; // Steps 5-6
    private static final long TIMEOUT_HOST_PREP_MS    = 5_000;  // Steps 7-10
    private static final long TIMEOUT_READY_MS        = 5_000;  // Steps 11-12

    // -------------------------------------------------------
    // Active sessions — one per player attempting a transfer
    // Key: player UUID string
    // -------------------------------------------------------
    private static final Map<String, TransferSession> activeSessions = new ConcurrentHashMap<>();

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

        // Don't start a new session if one is already in progress for this player
        if (activeSessions.containsKey(playerUuid)) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Transfer already in progress for player {}, ignoring new attempt.", playerUuid
            );
            return;
        }

        boolean isPortalPath = (originPortalUuid != null);
        boolean leapForward = ProfileManager.isLeapForwardPresent();

        // Create a placeholder session — transferKey will be filled in after probe
        TransferSession session = new TransferSession(
            null, // transferKey not yet known
            targetAddress,
            originAddress,
            isPortalPath,
            originPortalUuid,
            leapForward
        );
        activeSessions.put(playerUuid, session);

        // Run the probe on a background thread — never block the game thread
        Thread probeThread = new Thread(() -> runProbe(playerUuid, session), "LeapPad-Probe");
        probeThread.setDaemon(true);
        probeThread.start();
    }

    // -------------------------------------------------------
    // Step 3 — probe
    // -------------------------------------------------------

    private static void runProbe(String playerUuid, TransferSession session) {
        String leapForwardFlag = session.leapForwardPresent ? "present" : "absent";
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Probing {} for player {} (Leap! Forward: {})",
            session.targetAddress, playerUuid, leapForwardFlag
        );

        // Parse host and port from targetAddress (format: "host:port")
        String host;
        int port;
        try {
            int colon = session.targetAddress.lastIndexOf(':');
            host = session.targetAddress.substring(0, colon);
            port = Integer.parseInt(session.targetAddress.substring(colon + 1));
        } catch (Exception e) {
            failSession(playerUuid, session, "Step 3", "Could not parse target address: " + session.targetAddress);
            return;
        }

        // Get this client's external IP — for now resolved from ProfileManager cache
        // (Full async IP fetch logic lives in FabricCommandRegistry for /leappad ip,
        // and is reused here via ProfileManager.getCachedExternalIp())
        String clientIp = ProfileManager.getCachedExternalIp();
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = "unknown";
        }

        WorldPinger.PingOutcome outcome = WorldPinger.probe(
            host, port, clientIp, session.leapForwardPresent
        );

        switch (outcome.result) {
            case UNREACHABLE -> {
                // Gate never releases — player stays on current screen
                LeapPadCommon.LOGGER.info("[Leap! Pad] Target unreachable: {}", session.targetAddress);
                notifyPlayerPortalInactive(playerUuid);
                discardSession(playerUuid);
            }
            case REACHABLE_NO_LP -> {
                // Show warning screen — player can cancel or confirm
                session.advanceTo(TransferSession.TransferState.AWAITING_GATE);
                notifyPlayerNoLeapPad(playerUuid, session.targetAddress);
            }
            case REACHABLE_HAS_LP -> {
                // Store the transfer key and move to profile check
                // Sessions are immutable on transferKey — create a replacement
                TransferSession updated = new TransferSession(
                    outcome.transferKey,
                    session.targetAddress,
                    session.originAddress,
                    session.isPortalPath,
                    session.originPortalUuid,
                    session.leapForwardPresent
                );
                activeSessions.put(playerUuid, updated);

                // If profile string is already set, skip selector (step 6 directly)
                String activeProfile = ProfileManager.getActiveProfileUuid();
                if (activeProfile != null && !activeProfile.isEmpty()) {
                    updated.advanceTo(TransferSession.TransferState.SENDING_DAT);
                    sendProfileDat(playerUuid, updated);
                } else {
                    // Open profile selector (step 5)
                    updated.advanceTo(TransferSession.TransferState.AWAITING_PROFILE);
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
        byte[] datBlob = ProfileManager.getActiveDatBlob();

        if (profileUuid == null || datBlob == null) {
            // No profile — proceed without sending dat (host will use vanilla dat)
            session.advanceTo(TransferSession.TransferState.AWAITING_HOST_PREP);
            notifyHostReady(playerUuid, session);
            return;
        }

        // Actual packet sending is handled by FabricNetworking via the PacketSender interface
        packetSender.sendProfileDat(playerUuid, profileUuid, datBlob, session.leapForwardPresent);
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
        packetSender.sendUuidConfirm(playerUuid, agreedUuid);
    }

    // -------------------------------------------------------
    // Steps 11-12 — READY handshake
    // -------------------------------------------------------

    // Called when the host sends the READY echo (step 12)
    public static void onReadyEchoReceived(String playerUuid) {
        TransferSession session = activeSessions.get(playerUuid);
        if (session == null) return;

        session.advanceTo(TransferSession.TransferState.COMPLETE);

        // Release the gate — vanilla join runs now
        gateReleaser.releaseGate(playerUuid);

        // Record cooldown timestamp
        cooldownMap.put(playerUuid, System.currentTimeMillis());

        discardSession(playerUuid);
        LeapPadCommon.LOGGER.info("[Leap! Pad] Gate released for player {}", playerUuid);
    }

    // -------------------------------------------------------
    // Timeout checking
    // -------------------------------------------------------

    private static void checkTimeouts() {
        for (Map.Entry<String, TransferSession> entry : activeSessions.entrySet()) {
            String playerUuid = entry.getKey();
            TransferSession session = entry.getValue();
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
    }

    // Generates a portal UUID at the configured active length
    private static String generatePortalUuid() {
        // Full UUID is too long — trim to active length, pad/truncate as needed
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
    // These are implemented in the fabric/ subproject and injected at startup.
    // They allow common code to trigger client-side UI and packet sends
    // without importing Fabric-specific classes directly.
    // -------------------------------------------------------

    public interface PacketSender {
        void sendProfileDat(String playerUuid, String profileUuid, byte[] datBlob, boolean leapForward);
        void sendUuidConfirm(String playerUuid, String agreedUuid);
        void sendReady(String playerUuid, String transferKey);
    }

    public interface GateReleaser {
        void releaseGate(String playerUuid);
    }

    public interface PlayerNotifier {
        void showPortalInactive(String playerUuid);
        void showNoLeapPad(String playerUuid, String targetAddress);
        void showTimeout(String playerUuid);
        void openProfileSelector(String playerUuid);
        void notifyHostReady(String playerUuid, TransferSession session);
    }

    // Injected by LeapPadFabricClient at startup
    private static PacketSender packetSender;
    private static GateReleaser gateReleaser;
    private static PlayerNotifier playerNotifier;

    public static void setPacketSender(PacketSender sender) { packetSender = sender; }
    public static void setGateReleaser(GateReleaser releaser) { gateReleaser = releaser; }
    public static void setPlayerNotifier(PlayerNotifier notifier) { playerNotifier = notifier; }

    // Convenience wrappers that null-check before calling notifier
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
