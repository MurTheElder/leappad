package elder.leapp.transfer;

// TransferOrchestrator.java
// The main coordinator for all connection attempts.
// Drives the full pre-connection sequence and triggers vanilla connect only
// when all pre-arrival work is complete.
//
// Architecture:
//   - Portal path: LeapPortalBlock sends leappad:portal_initiate S→C packet.
//     Client receives it and calls onConnectionAttempt() directly.
//   - Direct connect / server list path: ConnectScreenMixin injects into
//     method_36877 (remap=false), cancels the initial call, and calls
//     onConnectionAttempt() with the address and server data.
//   - Both paths run the same probe → profile → dat → host prep → deconfliction
//     → READY sequence on background threads.
//   - When READY echo is received (step 12), VanillaConnectTrigger.connect()
//     is called. FabricReconnectHandler implements this: it disconnects the
//     player from their current world and calls method_36877 via @Invoker
//     to open a fresh ConnectScreen and connect to the target.
//   - ConnectScreenMixin sees the session is COMPLETE and does not cancel.
//     Vanilla join runs normally.
//
// Removed: GateReleaser, PortalConnectTrigger, triggerPortalConnect().
// Added: VanillaConnectTrigger, isSessionComplete(), onVanillaConnectCompleted().

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

    private static final long TIMEOUT_PROBE_MS     = 5_000;
    private static final long TIMEOUT_PROFILE_MS   = 15_000;
    private static final long TIMEOUT_HOST_PREP_MS = 5_000;
    private static final long TIMEOUT_READY_MS     = 5_000;

    private static final Map<String, TransferSession> activeSessions =
        new ConcurrentHashMap<>();

    private static final Set<String> pendingPlayerUuids =
        ConcurrentHashMap.newKeySet();

    private static final Map<String, Long> cooldownMap = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeapPad-TimeoutWatcher");
            t.setDaemon(true);
            return t;
        });

    static {
        scheduler.scheduleAtFixedRate(
            TransferOrchestrator::checkTimeouts, 1, 1, TimeUnit.SECONDS
        );
    }

    // -------------------------------------------------------
    // Entry point
    // Called by ConnectScreenMixin (direct connect / server list)
    // and by FabricNetworking on receipt of leappad:portal_initiate (portal path).
    // -------------------------------------------------------
    public static void onConnectionAttempt(String playerUuid,
                                           String targetAddress,
                                           String originPortalUuid,
                                           String originAddress) {

        if (activeSessions.containsKey(playerUuid) || pendingPlayerUuids.contains(playerUuid)) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Transfer already in progress for player {}, ignoring.", playerUuid
            );
            return;
        }

        boolean isPortalPath = (originPortalUuid != null);
        boolean leapForward  = ProfileManager.isLeapForwardPresent();

        pendingPlayerUuids.add(playerUuid);

        final String  capturedTarget     = targetAddress;
        final String  capturedOriginUuid = originPortalUuid;
        final String  capturedOriginAddr = originAddress;
        final boolean capturedLf        = leapForward;
        final boolean capturedPortal    = isPortalPath;

        Thread probeThread = new Thread(
            () -> runProbe(playerUuid, capturedTarget, capturedOriginUuid,
                           capturedOriginAddr, capturedPortal, capturedLf),
            "LeapPad-Probe"
        );
        probeThread.setDaemon(true);
        probeThread.start();
    }

    // -------------------------------------------------------
    // Session state check — used by ConnectScreenMixin
    // Returns true when the sequence is fully complete and the vanilla
    // connect triggered by FabricReconnectHandler should be allowed through.
    // -------------------------------------------------------
    public static boolean isSessionComplete(String playerUuid) {
        TransferSession session = activeSessions.get(playerUuid);
        return session != null && session.state == TransferSession.TransferState.COMPLETE;
    }

    // Called by ConnectScreenMixin after it lets the vanilla connect through.
    // Clears the COMPLETE session so the player can make future transfers.
    public static void onVanillaConnectCompleted(String playerUuid) {
        discardSession(playerUuid);
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
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Probing {} for player {} (LF: {})",
            targetAddress, playerUuid, leapForward ? "present" : "absent"
        );

        String host;
        int port;
        try {
            int colon = targetAddress.lastIndexOf(':');
            host = targetAddress.substring(0, colon);
            port = Integer.parseInt(targetAddress.substring(colon + 1));
        } catch (Exception e) {
            pendingPlayerUuids.remove(playerUuid);
            LeapPadCommon.LOGGER.error(
                "[Leap! Pad] Step 3 failed for player {}: bad address: {}",
                playerUuid, targetAddress
            );
            notifyPlayerPortalInactive(playerUuid);
            return;
        }

        String clientIp = ProfileManager.getCachedExternalIp();
        if (clientIp == null || clientIp.isEmpty()) clientIp = "unknown";

        WorldPinger.PingOutcome outcome = WorldPinger.probe(host, port, clientIp, leapForward);

        switch (outcome.result) {
            case UNREACHABLE -> {
                LeapPadCommon.LOGGER.info("[Leap! Pad] Target unreachable: {}", targetAddress);
                pendingPlayerUuids.remove(playerUuid);
                notifyPlayerPortalInactive(playerUuid);
            }
            case REACHABLE_NO_LP -> {
                TransferSession noLpSession = new TransferSession(
                    "", targetAddress, originAddress,
                    isPortalPath, originPortalUuid, leapForward
                );
                noLpSession.advanceTo(TransferSession.TransferState.AWAITING_GATE);
                pendingPlayerUuids.remove(playerUuid);
                activeSessions.put(playerUuid, noLpSession);
                notifyPlayerNoLeapPad(playerUuid, targetAddress);
            }
            case REACHABLE_HAS_LP -> {
                TransferSession session = new TransferSession(
                    outcome.transferKey, targetAddress, originAddress,
                    isPortalPath, originPortalUuid, leapForward
                );
                pendingPlayerUuids.remove(playerUuid);
                activeSessions.put(playerUuid, session);

                String activeProfile = ProfileManager.getActiveProfileUuid();
                if (activeProfile != null && !activeProfile.isEmpty()) {
                    session.advanceTo(TransferSession.TransferState.SENDING_DAT);
                    sendProfileDat(playerUuid, session);
                } else {
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
            session.advanceTo(TransferSession.TransferState.AWAITING_HOST_PREP);
            notifyHostReady(playerUuid, session);
            return;
        }

        if (packetSender != null) {
            packetSender.sendProfileDat(playerUuid, profileUuid, datBlob, session.leapForwardPresent);
        }
        session.advanceTo(TransferSession.TransferState.AWAITING_HOST_PREP);
    }

    // -------------------------------------------------------
    // Steps 8-10 — UUID deconfliction (portal path only)
    // -------------------------------------------------------

    public static void onUuidListReceived(String playerUuid, String[] hostUuids) {
        TransferSession session = activeSessions.get(playerUuid);
        if (session == null || !session.isPortalPath) return;

        session.advanceTo(TransferSession.TransferState.DECONFLICTING);

        String ourUuid = session.originPortalUuid;
        boolean conflict = false;
        for (String hostUuid : hostUuids) {
            if (hostUuid.equals(ourUuid)) { conflict = true; break; }
        }

        String agreedUuid;
        if (conflict) {
            agreedUuid = generatePortalUuid();
            PortalRegistry.updatePortalUuid(session.originPortalUuid, agreedUuid);
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] UUID conflict: {} → {}", ourUuid, agreedUuid
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

    // Called when the host sends the READY echo (step 12).
    // Calls VanillaConnectTrigger — implemented by FabricReconnectHandler.
    // Session stays in COMPLETE state so ConnectScreenMixin can check it.
    // Cleared by onVanillaConnectCompleted() after mixin confirms passthrough.
    public static void onReadyEchoReceived(String playerUuid) {
        TransferSession session = activeSessions.get(playerUuid);
        if (session == null) return;

        session.advanceTo(TransferSession.TransferState.COMPLETE);

        if (vanillaConnectTrigger != null) {
            vanillaConnectTrigger.connect(playerUuid, session.targetAddress);
        } else {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] VanillaConnectTrigger not injected — cannot connect player {}.",
                playerUuid
            );
        }

        cooldownMap.put(playerUuid, System.currentTimeMillis());
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Sequence complete for player {} — vanilla connect triggered.", playerUuid
        );
    }

    // -------------------------------------------------------
    // Timeout checking
    // -------------------------------------------------------

    private static void checkTimeouts() {
        for (Map.Entry<String, TransferSession> entry : activeSessions.entrySet()) {
            String playerUuid       = entry.getKey();
            TransferSession session = entry.getValue();

            // Never time out COMPLETE sessions — they're awaiting mixin confirmation
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
            "[Leap! Pad] Failed at {} for {}: {}", step, playerUuid, reason
        );
        session.advanceTo(TransferSession.TransferState.FAILED);
        notifyPlayerTimeout(playerUuid);
        discardSession(playerUuid);
    }

    private static void discardSession(String playerUuid) {
        activeSessions.remove(playerUuid);
        pendingPlayerUuids.remove(playerUuid);
    }

    private static String generatePortalUuid() {
        String full = UUID.randomUUID().toString().replace("-", "");
        int len = elder.leapp.config.LeapPadConfig.portalDesignationActiveLength;
        if (full.length() >= len) return full.substring(0, len);
        return full + "0".repeat(len - full.length());
    }

    public static boolean isOnCooldown(String playerUuid) {
        Long last = cooldownMap.get(playerUuid);
        if (last == null) return false;
        return (System.currentTimeMillis() - last) <
               (elder.leapp.config.LeapPadConfig.portalCooldownSeconds * 1000L);
    }

    // -------------------------------------------------------
    // Platform bridge interfaces
    // -------------------------------------------------------

    public interface PacketSender {
        void sendProfileDat(String playerUuid, String profileUuid,
                            byte[] datBlob, boolean leapForward);
        void sendUuidConfirm(String playerUuid, String agreedUuid);
        void sendReady(String playerUuid, String transferKey);
    }

    // Replaces GateReleaser and PortalConnectTrigger.
    // Implemented by FabricReconnectHandler in the fabric subproject.
    // Called when the full sequence is done and vanilla connect should fire.
    public interface VanillaConnectTrigger {
        void connect(String playerUuid, String targetAddress);
    }

    public interface PlayerNotifier {
        void showPortalInactive(String playerUuid);
        void showNoLeapPad(String playerUuid, String targetAddress);
        void showTimeout(String playerUuid);
        void openProfileSelector(String playerUuid);
        void notifyHostReady(String playerUuid, TransferSession session);
    }

    private static PacketSender          packetSender;
    private static VanillaConnectTrigger vanillaConnectTrigger;
    private static PlayerNotifier        playerNotifier;

    public static void setPacketSender(PacketSender s)                    { packetSender = s; }
    public static void setVanillaConnectTrigger(VanillaConnectTrigger t)  { vanillaConnectTrigger = t; }
    public static void setPlayerNotifier(PlayerNotifier n)                { playerNotifier = n; }

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
