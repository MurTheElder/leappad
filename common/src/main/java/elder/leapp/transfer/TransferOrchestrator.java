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
//     startConnecting(), cancels the initial call, and calls onConnectionAttempt().
//   - Both paths run the same probe → profile → dat → host prep → deconfliction
//     → READY sequence on background threads.
//   - When READY echo is received (step 12), VanillaConnectTrigger.connect()
//     is called. FabricReconnectHandler disconnects from the current world
//     and calls ConnectScreen.startConnecting() to open a fresh ConnectScreen.
//   - ConnectScreenMixin sees the session is COMPLETE and does not cancel.
//     Vanilla join runs normally.
//
// B1+B2 fix: sendProfileDat() no longer calls notifyHostReady() directly.
//   - Portal path: session advances to AWAITING_HOST_PREP and waits. The host
//     receives the dat, does prep work (mirror portal etc.), sends the UUID list
//     (step 8). Client deconflicts, sends UUID confirm (step 10). Client then
//     advances to AWAITING_READY_ECHO and sends READY (step 11).
//   - Non-portal path: no UUID deconfliction. After dat send the client advances
//     to AWAITING_READY_ECHO and sends READY immediately (no host prep to wait on).
//   onUuidConfirmSent() is the new client-side hook that fires after step 10 on
//   the portal path and triggers READY. onReadyEchoReceived() handles step 12.
//
// B4 fix: discardSession() now takes the session object so it can call
//   LeapPortalBlock.clearTrigger() for portal-path sessions.
//   onVanillaConnectCompleted() does the same.

import elder.leapp.LeapPadCommon;
import elder.leapp.portal.LeapPortalBlock;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.profile.ProfileManager;
import net.minecraft.core.BlockPos;

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
    // Session state checks — used by ConnectScreenMixin
    // -------------------------------------------------------

    // Returns true when the sequence is fully complete and the vanilla connect
    // triggered by FabricReconnectHandler should be allowed through.
    public static boolean isSessionComplete(String playerUuid) {
        TransferSession session = activeSessions.get(playerUuid);
        return session != null && session.state == TransferSession.TransferState.COMPLETE;
    }

    // Called by ConnectScreenMixin after it lets the vanilla connect through.
    // Clears the COMPLETE session and releases the portal re-entry guard (B4).
    public static void onVanillaConnectCompleted(String playerUuid) {
        TransferSession session = activeSessions.get(playerUuid);
        discardSession(playerUuid, session);
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
            // N2 fix: clear portal trigger on early exit — no session was created.
            if (isPortalPath) clearPortalTrigger(playerUuid);
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
                // N2 fix: clear the portal re-entry guard so the player can try again.
                // No session was created, so discardSession() won't be called.
                if (isPortalPath) clearPortalTrigger(playerUuid);
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
    //
    // B1+B2 fix: this method no longer calls notifyHostReady() directly.
    //
    // Portal path:
    //   Dat is sent. Session advances to AWAITING_HOST_PREP and waits.
    //   The host receives the dat, does step 7 prep work (mirror portal,
    //   dat file write, Leap! Forward cache), then sends the UUID list (step 8).
    //   The host-side trigger for this is in FabricNetworking.registerServerReceivers()
    //   which calls onHostPrepComplete() after writing the dat file.
    //
    // Non-portal path:
    //   No UUID deconfliction. After dat is sent (or skipped), the client
    //   advances directly to AWAITING_READY_ECHO and sends READY (step 11).
    //   The host will echo it back and vanilla connect fires.
    // -------------------------------------------------------

    private static void sendProfileDat(String playerUuid, TransferSession session) {
        String profileUuid = ProfileManager.getActiveProfileUuid();
        byte[] datBlob     = ProfileManager.getActiveDatBlob();

        boolean hasProfile = profileUuid != null && datBlob != null;

        if (hasProfile) {
            // Send the full profile dat to the host.
            if (packetSender != null) {
                packetSender.sendProfileDat(playerUuid, profileUuid, datBlob, session.leapForwardPresent);
            }
        } else if (session.isPortalPath) {
            // N1 fix: no active profile, but this is a portal path.
            // The host uses PROFILE_DAT_SEND as the trigger to start its prep work
            // (step 7 → onHostPrepComplete). If we skip sending, the host never
            // gets that signal and the session hangs in AWAITING_HOST_PREP.
            // Send an empty packet (empty profile UUID, zero-byte blob) so the host
            // receives the signal. The host will write nothing meaningful for an
            // empty blob and still call onHostPrepComplete().
            if (packetSender != null) {
                packetSender.sendProfileDat(playerUuid, "", new byte[0], session.leapForwardPresent);
            }
        }
        // Non-portal path with no profile: nothing is sent. onDatSendComplete()
        // routes directly to AWAITING_READY_ECHO below.

        onDatSendComplete(playerUuid, session);
    }

    // Called after dat send (or skip) to route portal vs non-portal path.
    private static void onDatSendComplete(String playerUuid, TransferSession session) {
        if (session.isPortalPath) {
            // Portal path: advance to AWAITING_HOST_PREP and wait.
            // The host will send the UUID list (step 8) after completing step 7.
            session.advanceTo(TransferSession.TransferState.AWAITING_HOST_PREP);
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Dat sent for {} — waiting for host prep (step 7).", playerUuid
            );
        } else {
            // Non-portal path: no UUID deconfliction, send READY now.
            session.advanceTo(TransferSession.TransferState.AWAITING_READY_ECHO);
            notifyHostReady(playerUuid, session);
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Non-portal path for {} — READY sent.", playerUuid
            );
        }
    }

    // -------------------------------------------------------
    // Step 7 — host prep complete (portal path only)
    //
    // Called by FabricNetworking on the host side after writing the dat file
    // and completing mirror portal placement. The host then sends the UUID list.
    // This is triggered server-side — the client receives the UUID list packet
    // and onUuidListReceived() fires client-side.
    // -------------------------------------------------------

    // Called server-side by FabricNetworking after dat is written and prep is done.
    // Tells the platform layer to send the UUID list to the client (step 8).
    public static void onHostPrepComplete(String playerUuid) {
        TransferSession session = activeSessions.get(playerUuid);
        if (session == null) return;
        if (!session.isPortalPath) return;

        session.advanceTo(TransferSession.TransferState.AWAITING_UUID_LIST);

        // N4: Build the mirror portal (step 7).
        // MirrorPortalManager.placePortal() needs a ServerLevel reference which
        // common code cannot hold directly. We delegate to the ServerLevelProvider
        // bridge which is injected from the fabric subproject (LeapPadFabric).
        // The origin portal's first corner is used as the starting XZ for placement.
        if (serverLevelProvider != null) {
            String originPortalUuid = session.originPortalUuid;
            elder.leapp.portal.PortalRegistry.PortalEntry originEntry =
                PortalRegistry.getEntry(originPortalUuid);

            BlockPos originCorner = (originEntry != null && !originEntry.corners.isEmpty())
                ? originEntry.corners.get(0)
                : new BlockPos(0, 64, 0); // Fallback if origin portal not found

            // placePortal returns a PlacementOutcome with a provisional UUID.
            // We register it as pending so updateMirrorPortalUuid() can rename it
            // to the agreed UUID when uuid_confirm arrives at step 10.
            serverLevelProvider.withOverworldLevel(level -> {
                elder.leapp.portal.MirrorPortalManager.PlacementOutcome outcome =
                    elder.leapp.portal.MirrorPortalManager.placePortal(
                        originCorner, session.originAddress, level
                    );
                if (outcome.result == elder.leapp.portal.MirrorPortalManager.PlacementResult.SUCCESS) {
                    PortalRegistry.registerPendingMirrorPortal(playerUuid, outcome.portalUuid);
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] Mirror portal built for player {} — provisional UUID: {}",
                        playerUuid, outcome.portalUuid
                    );
                } else {
                    LeapPadCommon.LOGGER.warn(
                        "[Leap! Pad] Mirror portal placement failed for player {} — " +
                        "player will spawn at world default spawn.", playerUuid
                    );
                }
            });
        } else {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] ServerLevelProvider not injected — mirror portal not built for {}.", playerUuid
            );
        }

        // Collect all portal UUIDs from the registry and send them to the client.
        String[] uuids = PortalRegistry.getAll().keySet().toArray(new String[0]);
        if (hostPrepNotifier != null) {
            hostPrepNotifier.sendUuidListToClient(playerUuid, uuids);
        } else {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] HostPrepNotifier not injected — UUID list not sent for {}.", playerUuid
            );
        }
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Host prep complete for {} — UUID list sent ({} entries).",
            playerUuid, uuids.length
        );
    }

    // -------------------------------------------------------
    // Steps 8-10 — UUID deconfliction (portal path only)
    // -------------------------------------------------------

    // Called client-side when the host sends its UUID list (step 8).
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
        // N4: include originAddress so the host can link the mirror portal back.
        if (packetSender != null) packetSender.sendUuidConfirm(playerUuid, agreedUuid, session.originAddress);

        // UUID confirm sent (step 10). All client-side pre-connection work is done.
        // Advance to AWAITING_READY_ECHO and send the READY signal (step 11).
        session.advanceTo(TransferSession.TransferState.AWAITING_READY_ECHO);
        notifyHostReady(playerUuid, session);
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Deconfliction complete for {} — READY sent.", playerUuid
        );
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
        discardSession(playerUuid, session);
    }

    // B4 fix: takes session so it can clear the portal re-entry guard.
    // The guard must be cleared on both success and failure paths so the
    // player can use portals again after a completed or failed transfer.
    private static void discardSession(String playerUuid, TransferSession session) {
        activeSessions.remove(playerUuid);
        pendingPlayerUuids.remove(playerUuid);
        if (session != null && session.isPortalPath) {
            clearPortalTrigger(playerUuid);
        }
    }

    // B4/N2: Clears the portal re-entry guard in LeapPortalBlock.
    // Called from discardSession() for portal-path sessions, and directly
    // for early-exit paths (UNREACHABLE, bad address) where no session exists.
    private static void clearPortalTrigger(String playerUuid) {
        try {
            LeapPortalBlock.clearTrigger(UUID.fromString(playerUuid));
        } catch (IllegalArgumentException e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Could not clear portal trigger for {}: bad UUID format.", playerUuid
            );
        }
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
        void sendUuidConfirm(String playerUuid, String agreedUuid, String originAddress);
        void sendReady(String playerUuid, String transferKey);
    }

    // Called when the full sequence is done and vanilla connect should fire.
    // Implemented by FabricReconnectHandler in the fabric subproject.
    public interface VanillaConnectTrigger {
        void connect(String playerUuid, String targetAddress);
    }

    // Called server-side after host prep is complete (step 7) to send the
    // UUID list to the client (step 8). Implemented in FabricNetworking.
    public interface HostPrepNotifier {
        void sendUuidListToClient(String playerUuid, String[] uuids);
    }

    // N4: Provides access to the server's overworld ServerLevel for
    // MirrorPortalManager. Common code cannot hold a ServerLevel reference
    // directly — injected from LeapPadFabric at world start.
    public interface ServerLevelProvider {
        void withOverworldLevel(java.util.function.Consumer<net.minecraft.server.level.ServerLevel> consumer);
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
    private static HostPrepNotifier      hostPrepNotifier;
    private static ServerLevelProvider   serverLevelProvider;
    private static PlayerNotifier        playerNotifier;

    public static void setPacketSender(PacketSender s)                       { packetSender = s; }
    public static void setVanillaConnectTrigger(VanillaConnectTrigger t)     { vanillaConnectTrigger = t; }
    public static void setHostPrepNotifier(HostPrepNotifier n)               { hostPrepNotifier = n; }
    public static void setServerLevelProvider(ServerLevelProvider p)         { serverLevelProvider = p; }
    public static void setPlayerNotifier(PlayerNotifier n)                   { playerNotifier = n; }

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
