package elder.leapp.transfer;

// TransferOrchestrator.java
// The public-facing coordinator for all connection attempts.
// After the TO1 split, this class is the API surface only — it delegates
// all sequence logic to TransferSequencer and all session state to
// TransferSessionManager.
//
// Responsibilities:
//   - Entry points: onConnectionAttempt(), onProfileSelected(),
//     cancelFromProfileSelector(), onVanillaConnectCompleted(), isSessionComplete()
//   - All bridge interfaces and their setters/getters
//   - Notification helper methods (thin delegates to playerNotifier)
//
// B1 compliance: player UUID is sourced by the caller (ConnectScreenMixin or
// FabricNetworking packet receivers) using:
//   Minecraft.getInstance().getUser().getGameProfile().getId().toString()
// This class never accesses mc.player directly.
//
// TO1 split: sequence logic → TransferSequencer
//            session state  → TransferSessionManager

import elder.leapp.LeapPadCommon;
import elder.leapp.profile.ProfileManager;

public class TransferOrchestrator {

    // -------------------------------------------------------
    // Entry points
    // -------------------------------------------------------

    // Called by ConnectScreenMixin (direct connect / server list) and by
    // FabricNetworking on receipt of leappad:portal_initiate (portal path).
    //
    // B1: playerUuid arrives pre-resolved by the caller via
    // Minecraft.getInstance().getUser().getGameProfile().getId().toString()
    public static void onConnectionAttempt(String playerUuid,
                                           String targetAddress,
                                           String originPortalUuid,
                                           String originAddress) {
        if (TransferSessionManager.hasActiveSession(playerUuid)) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Transfer already in progress for player {}, ignoring.", playerUuid
            );
            return;
        }

        boolean isPortalPath = (originPortalUuid != null);
        boolean leapForward  = ProfileManager.isLeapForwardPresent();

        TransferSessionManager.pendingPlayerUuids.add(playerUuid);

        final String  capturedTarget     = targetAddress;
        final String  capturedOriginUuid = originPortalUuid;
        final String  capturedOriginAddr = originAddress;
        final boolean capturedLf        = leapForward;
        final boolean capturedPortal    = isPortalPath;

        Thread probeThread = new Thread(
            () -> TransferSequencer.runProbe(
                playerUuid, capturedTarget, capturedOriginUuid,
                capturedOriginAddr, capturedPortal, capturedLf
            ),
            "LeapPad-Probe"
        );
        probeThread.setDaemon(true);
        probeThread.start();
    }

    // Called by ConnectScreenMixin after it lets the vanilla connect through.
    public static void onVanillaConnectCompleted(String playerUuid) {
        TransferSession session = TransferSessionManager.getSession(playerUuid);
        TransferSessionManager.discardSession(playerUuid, session);
    }

    // Called by ProfileSelectorScreen / PortalProfileSelectorScreen when the
    // player makes their selection. Resumes sequence from AWAITING_PROFILE.
    public static void onProfileSelected(String playerUuid) {
        TransferSession session = TransferSessionManager.getSession(playerUuid);
        if (session == null) return;
        session.advanceTo(TransferSession.TransferState.SENDING_DAT);
        Thread t = new Thread(
            () -> TransferSequencer.sendProfileDat(playerUuid, session),
            "LeapPad-DatSend"
        );
        t.setDaemon(true);
        t.start();
    }

    // Called by ProfileSelectorScreen / PortalProfileSelectorScreen on cancel.
    public static void cancelFromProfileSelector(String playerUuid) {
        TransferSession session = TransferSessionManager.getSession(playerUuid);
        LeapPadCommon.LOGGER.info("[Leap! Pad] Profile selection cancelled for {}.", playerUuid);
        TransferSessionManager.discardSession(playerUuid, session);
    }

    // -------------------------------------------------------
    // Session state queries — used by ConnectScreenMixin
    // -------------------------------------------------------

    // Returns true when the sequence is complete and the vanilla connect
    // triggered by FabricReconnectHandler should be allowed through.
    public static boolean isSessionComplete(String playerUuid) {
        TransferSession session = TransferSessionManager.getSession(playerUuid);
        return session != null && session.state == TransferSession.TransferState.COMPLETE;
    }

    public static boolean isOnCooldown(String playerUuid) {
        return TransferSessionManager.isOnCooldown(playerUuid);
    }

    // -------------------------------------------------------
    // Delegation to TransferSequencer (called by FabricNetworking)
    // -------------------------------------------------------

    public static void onHostPrepComplete(String playerUuid) {
        TransferSequencer.onHostPrepComplete(playerUuid);
    }

    public static void onUuidListReceived(String playerUuid, String[] hostUuids) {
        TransferSequencer.onUuidListReceived(playerUuid, hostUuids);
    }

    public static void onReadyEchoReceived(String playerUuid) {
        TransferSequencer.onReadyEchoReceived(playerUuid);
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

    // Implemented by FabricReconnectHandler. Called when the full sequence is done.
    public interface VanillaConnectTrigger {
        void connect(String playerUuid, String targetAddress);
    }

    // Called server-side after host prep is complete (step 7).
    public interface HostPrepNotifier {
        void sendUuidListToClient(String playerUuid, String[] uuids);
    }

    // Provides access to the server's overworld ServerLevel for MirrorPortalManager.
    public interface ServerLevelProvider {
        void withOverworldLevel(java.util.function.Consumer<net.minecraft.server.level.ServerLevel> consumer);
    }

    public interface PlayerNotifier {
        void showPortalInactive(String playerUuid);
        void showNoLeapPad(String playerUuid, String targetAddress);
        void showTimeout(String playerUuid);
        void openProfileSelector(String playerUuid, boolean isPortalPath, String targetAddress);
        void notifyHostReady(String playerUuid, TransferSession session);
    }

    // -------------------------------------------------------
    // Bridge interface storage and setters
    // -------------------------------------------------------

    private static PacketSender          packetSender;
    private static VanillaConnectTrigger vanillaConnectTrigger;
    private static HostPrepNotifier      hostPrepNotifier;
    private static ServerLevelProvider   serverLevelProvider;
    private static PlayerNotifier        playerNotifier;

    public static void setPacketSender(PacketSender s)                      { packetSender = s; }
    public static void setVanillaConnectTrigger(VanillaConnectTrigger t)    { vanillaConnectTrigger = t; }
    public static void setHostPrepNotifier(HostPrepNotifier n)              { hostPrepNotifier = n; }
    public static void setServerLevelProvider(ServerLevelProvider p)        { serverLevelProvider = p; }
    public static void setPlayerNotifier(PlayerNotifier n)                  { playerNotifier = n; }

    // Package-private getters — used by TransferSequencer and TransferSessionManager
    static PacketSender          getPacketSender()         { return packetSender; }
    static VanillaConnectTrigger getVanillaConnectTrigger(){ return vanillaConnectTrigger; }
    static HostPrepNotifier      getHostPrepNotifier()     { return hostPrepNotifier; }
    static ServerLevelProvider   getServerLevelProvider()  { return serverLevelProvider; }
    static PlayerNotifier        getPlayerNotifier()       { return playerNotifier; }
}
