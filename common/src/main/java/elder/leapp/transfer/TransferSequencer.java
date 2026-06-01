package elder.leapp.transfer;

// TransferSequencer.java
// Drives the step-by-step pre-connection sequence.
// Extracted from TransferOrchestrator as part of TO1 split.
//
// Responsibilities:
//   - Step 3: TCP probe to target world
//   - Step 6: profile dat send (or empty signal for no-profile portal path)
//   - Step 7: host prep complete trigger
//   - Steps 8-10: UUID deconfliction (portal path only)
//   - Steps 11-12: READY handshake
//
// This class holds no state of its own. All session state lives in
// TransferSessionManager. Bridge interfaces are read from TransferOrchestrator.
//
// B1 compliance: player UUID is always sourced from the caller
// (TransferOrchestrator.onConnectionAttempt receives it from ConnectScreenMixin
// which now uses Minecraft.getInstance().getUser().getGameProfile().getId()).
// No mc.player access anywhere in this class.
//
// SS2 fix: runProbe() wraps its body in try-finally to guarantee
// pendingPlayerUuids cleanup even on uncaught exception.

import elder.leapp.LeapPadCommon;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.profile.ProfileManager;
import net.minecraft.core.BlockPos;

import java.util.UUID;

public class TransferSequencer {

    // -------------------------------------------------------
    // Step 3 — probe
    // -------------------------------------------------------

    // Called on a background thread (LeapPad-Probe).
    // SS2 fix: try-finally ensures pendingPlayerUuids is always cleaned up.
    static void runProbe(String playerUuid,
                          String targetAddress,
                          String originPortalUuid,
                          String originAddress,
                          boolean isPortalPath,
                          boolean leapForward) {
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Probing {} for player {} (LF: {})",
            targetAddress, playerUuid, leapForward ? "present" : "absent"
        );

        try {
            String host;
            int port;
            try {
                int colon = targetAddress.lastIndexOf(':');
                host = targetAddress.substring(0, colon);
                port = Integer.parseInt(targetAddress.substring(colon + 1));
            } catch (Exception e) {
                LeapPadCommon.LOGGER.error(
                    "[Leap! Pad] Step 3 failed for player {}: bad address: {}",
                    playerUuid, targetAddress
                );
                notifyPortalInactive(playerUuid);
                if (isPortalPath) TransferSessionManager.clearPortalTrigger(playerUuid);
                return;
            }

            String clientIp = ProfileManager.getCachedExternalIp();
            if (clientIp == null || clientIp.isEmpty()) {
                // SS8: Cache is empty or expired — trigger async re-fetch for next attempt.
                // Uses IpRefreshCallback bridge (injected from LeapPadFabricClient) to keep
                // common code free of fabric-specific imports — required for NeoForge portability.
                // Use "unknown" for this probe; the refreshed IP will be ready on retry.
                TransferOrchestrator.IpRefreshCallback cb = TransferOrchestrator.getIpRefreshCallback();
                if (cb != null) cb.refresh();
                clientIp = "unknown";
            }

            WorldPinger.PingOutcome outcome = WorldPinger.probe(host, port, clientIp, leapForward);

            switch (outcome.result) {
                case UNREACHABLE -> {
                    LeapPadCommon.LOGGER.info("[Leap! Pad] Target unreachable: {}", targetAddress);
                    notifyPortalInactive(playerUuid);
                    if (isPortalPath) TransferSessionManager.clearPortalTrigger(playerUuid);
                }
                case REACHABLE_NO_LP -> {
                    TransferSession noLpSession = new TransferSession(
                        "", targetAddress, originAddress,
                        isPortalPath, originPortalUuid, leapForward
                    );
                    noLpSession.advanceTo(TransferSession.TransferState.AWAITING_GATE);
                    TransferSessionManager.putSession(playerUuid, noLpSession);
                    notifyNoLeapPad(playerUuid, targetAddress);
                }
                case REACHABLE_HAS_LP -> {
                    TransferSession session = new TransferSession(
                        outcome.transferKey, targetAddress, originAddress,
                        isPortalPath, originPortalUuid, leapForward
                    );
                    TransferSessionManager.putSession(playerUuid, session);

                    String activeProfile = ProfileManager.getActiveProfileUuid();
                    if (activeProfile != null && !activeProfile.isEmpty()) {
                        session.advanceTo(TransferSession.TransferState.SENDING_DAT);
                        sendProfileDat(playerUuid, session);
                    } else {
                        session.advanceTo(TransferSession.TransferState.AWAITING_PROFILE);
                        openProfileSelector(playerUuid, session);
                    }
                }
            }
        } finally {
            // SS2 fix: always remove from pending regardless of how the probe exits.
            TransferSessionManager.pendingPlayerUuids.remove(playerUuid);
        }
    }

    // -------------------------------------------------------
    // Step 6 — send profile dat
    // -------------------------------------------------------

    // Called on a background thread (LeapPad-DatSend) after profile selection,
    // or inline from runProbe when a profile is already set.
    static void sendProfileDat(String playerUuid, TransferSession session) {
        String profileUuid = ProfileManager.getActiveProfileUuid();
        byte[] datBlob     = ProfileManager.getActiveDatBlob();

        boolean hasProfile = profileUuid != null && datBlob != null;
        TransferOrchestrator.PacketSender sender = TransferOrchestrator.getPacketSender();

        if (hasProfile) {
            if (sender != null) {
                sender.sendProfileDat(playerUuid, profileUuid, datBlob, session.leapForwardPresent);
            }
        } else if (session.isPortalPath) {
            // N1 fix: portal path with no profile — send empty signal so the host
            // still receives the trigger to start its prep work (step 7).
            if (sender != null) {
                sender.sendProfileDat(playerUuid, "", new byte[0], session.leapForwardPresent);
            }
        }

        onDatSendComplete(playerUuid, session);
    }

    private static void onDatSendComplete(String playerUuid, TransferSession session) {
        if (session.isPortalPath) {
            session.advanceTo(TransferSession.TransferState.AWAITING_HOST_PREP);
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Dat sent for {} — waiting for host prep (step 7).", playerUuid
            );
        } else {
            session.advanceTo(TransferSession.TransferState.AWAITING_READY_ECHO);
            notifyHostReady(playerUuid, session);
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Non-portal path for {} — READY sent.", playerUuid
            );
        }
    }

    // -------------------------------------------------------
    // Step 7 — host prep complete (portal path only)
    // -------------------------------------------------------

    // Called server-side by FabricNetworking after dat is written.
    // Builds the mirror portal and sends the UUID list to the client.
    static void onHostPrepComplete(String playerUuid) {
        TransferSession session = TransferSessionManager.getSession(playerUuid);
        if (session == null || !session.isPortalPath) return;

        session.advanceTo(TransferSession.TransferState.AWAITING_UUID_LIST);

        // N4: Build the mirror portal using the ServerLevelProvider bridge.
        TransferOrchestrator.ServerLevelProvider levelProvider =
            TransferOrchestrator.getServerLevelProvider();

        if (levelProvider != null) {
            String originPortalUuid = session.originPortalUuid;
            PortalRegistry.PortalEntry originEntry =
                PortalRegistry.getEntry(originPortalUuid);

            BlockPos originCorner = (originEntry != null && !originEntry.corners.isEmpty())
                ? originEntry.corners.get(0)
                : new BlockPos(0, 64, 0);

            levelProvider.withOverworldLevel(level -> {
                elder.leapp.portal.MirrorPortalManager.PlacementOutcome outcome =
                    elder.leapp.portal.MirrorPortalManager.placePortal(
                        originCorner, session.originAddress, level
                    );
                if (outcome.result ==
                    elder.leapp.portal.MirrorPortalManager.PlacementResult.SUCCESS) {
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
                "[Leap! Pad] ServerLevelProvider not injected — mirror portal not built for {}.",
                playerUuid
            );
        }

        // Send UUID list to client for deconfliction (step 8).
        String[] uuids = PortalRegistry.getAll().keySet().toArray(new String[0]);
        TransferOrchestrator.HostPrepNotifier hostPrepNotifier =
            TransferOrchestrator.getHostPrepNotifier();
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
    static void onUuidListReceived(String playerUuid, String[] hostUuids) {
        TransferSession session = TransferSessionManager.getSession(playerUuid);
        if (session == null || !session.isPortalPath) return;

        session.advanceTo(TransferSession.TransferState.DECONFLICTING);

        String ourUuid  = session.originPortalUuid;
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

        TransferOrchestrator.PacketSender sender = TransferOrchestrator.getPacketSender();
        if (sender != null) sender.sendUuidConfirm(playerUuid, agreedUuid, session.originAddress);

        // UUID confirm sent (step 10). All client-side pre-connection work is done.
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
    // Session stays in COMPLETE state so ConnectScreenMixin can check it.
    static void onReadyEchoReceived(String playerUuid) {
        TransferSession session = TransferSessionManager.getSession(playerUuid);
        if (session == null) return;

        session.advanceTo(TransferSession.TransferState.COMPLETE);

        TransferOrchestrator.VanillaConnectTrigger trigger =
            TransferOrchestrator.getVanillaConnectTrigger();
        if (trigger != null) {
            trigger.connect(playerUuid, session.targetAddress);
        } else {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] VanillaConnectTrigger not injected — cannot connect player {}.",
                playerUuid
            );
        }

        TransferSessionManager.recordCooldown(playerUuid);
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Sequence complete for player {} — vanilla connect triggered.", playerUuid
        );
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private static String generatePortalUuid() {
        String full = UUID.randomUUID().toString().replace("-", "");
        int len = elder.leapp.config.LeapPadConfig.portalDesignationActiveLength;
        if (full.length() >= len) return full.substring(0, len);
        return full + "0".repeat(len - full.length());
    }

    private static void notifyPortalInactive(String playerUuid) {
        TransferOrchestrator.PlayerNotifier n = TransferOrchestrator.getPlayerNotifier();
        if (n != null) n.showPortalInactive(playerUuid);
    }

    private static void notifyNoLeapPad(String playerUuid, String address) {
        TransferOrchestrator.PlayerNotifier n = TransferOrchestrator.getPlayerNotifier();
        if (n != null) n.showNoLeapPad(playerUuid, address);
    }

    private static void openProfileSelector(String playerUuid, TransferSession session) {
        TransferOrchestrator.PlayerNotifier n = TransferOrchestrator.getPlayerNotifier();
        if (n != null) n.openProfileSelector(playerUuid, session.isPortalPath, session.targetAddress);
    }

    private static void notifyHostReady(String playerUuid, TransferSession session) {
        TransferOrchestrator.PlayerNotifier n = TransferOrchestrator.getPlayerNotifier();
        if (n != null) n.notifyHostReady(playerUuid, session);
    }
}
