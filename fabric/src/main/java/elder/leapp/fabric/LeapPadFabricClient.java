package elder.leapp.fabric;

// LeapPadFabricClient.java
// The Fabric client-side entry point for Leap! Pad.
//
// Responsibilities:
//   - Initialize ProfileManager with resolved paths and Leap! Forward flag (S4)
//   - Register client-side packet receivers via FabricNetworking
//   - Register the portal block render layer (C2)
//   - Inject bridge interfaces into TransferOrchestrator:
//       PacketSender          — delegates outbound packets to FabricNetworking
//       VanillaConnectTrigger — calls FabricReconnectHandler at sequence end
//       PlayerNotifier        — shows messages and screens (stubs for ST1, ST2)
//   - Inject PortalPacketSender into LeapPortalBlock
//
// Removed: GateReleaser, PortalConnectTrigger (replaced by VanillaConnectTrigger
// and the portal_initiate packet path).

import elder.leapp.LeapPadCommon;
import elder.leapp.fabric.network.FabricNetworking;
import elder.leapp.fabric.registry.FabricRegistrar;
import elder.leapp.fabric.transfer.FabricReconnectHandler;
import elder.leapp.fabric.ui.LanStatusHud;
import elder.leapp.fabric.ui.PortalProfileSelectorScreen;
import elder.leapp.fabric.ui.ProfileSelectorScreen;
import elder.leapp.portal.LeapPortalBlock;
import elder.leapp.profile.ProfileManager;
import elder.leapp.transfer.TransferOrchestrator;
import elder.leapp.transfer.TransferSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class LeapPadFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // S4: Resolve profilesDir and leapForwardPresent in Fabric-specific code
        // and pass them into ProfileManager.init() to keep common code loader-agnostic.
        Path profilesDir = FabricLoader.getInstance()
            .getGameDir()
            .resolve("leappad")
            .resolve("profiles");
        boolean leapForwardPresent = FabricLoader.getInstance()
            .isModLoaded("leapforward");
        ProfileManager.init(profilesDir, leapForwardPresent);

        // Register all client-side packet receivers.
        FabricNetworking.registerClientReceivers();

        // C2: Set the portal block render layer to translucent.
        FabricRegistrar.registerClientSide();

        // -------------------------------------------------------
        // Inject bridge interfaces into TransferOrchestrator.
        // -------------------------------------------------------

        // PacketSender — delegates outbound packet calls to FabricNetworking.
        TransferOrchestrator.setPacketSender(new TransferOrchestrator.PacketSender() {
            public void sendProfileDat(String playerUuid, String profileUuid,
                                       byte[] datBlob, boolean leapForward) {
                FabricNetworking.sendProfileDatToServer(profileUuid, datBlob, leapForward);
            }
            public void sendUuidConfirm(String playerUuid, String agreedUuid, String originAddress) {
                FabricNetworking.sendUuidConfirmToServer(agreedUuid, originAddress);
            }
            public void sendReady(String playerUuid, String transferKey) {
                // Not called by the orchestrator — READY is sent via notifyHostReady()
                // in the PlayerNotifier below. This method exists to satisfy the interface.
                FabricNetworking.sendReadyToServer(transferKey);
            }
        });

        // VanillaConnectTrigger — called when the full sequence is complete.
        // FabricReconnectHandler disconnects from the current world and calls
        // ConnectScreen.startConnecting() directly (it is public in 1.20.1).
        // ConnectScreenMixin sees the session is COMPLETE and lets it through.
        TransferOrchestrator.setVanillaConnectTrigger(FabricReconnectHandler.INSTANCE);

        // PlayerNotifier — shows messages and opens screens.
        TransferOrchestrator.setPlayerNotifier(new TransferOrchestrator.PlayerNotifier() {
            public void showPortalInactive(String playerUuid) {
                // TODO ST: show "Portal is inactive." message to player
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] Portal inactive — stub notify for {}.", playerUuid
                );
            }
            public void showNoLeapPad(String playerUuid, String targetAddress) {
                // TODO ST1: open WarningScreen
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] No Leap! Pad on {} — warning screen stub.", targetAddress
                );
            }
            public void showTimeout(String playerUuid) {
                // TODO ST: show "Connection timed out." message to player
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] Connection timed out — stub notify for {}.", playerUuid
                );
            }
            public void openProfileSelector(String playerUuid, boolean isPortalPath, String targetAddress) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                mc.execute(() -> {
                    if (isPortalPath) {
                        mc.setScreen(new PortalProfileSelectorScreen(mc.screen, playerUuid, targetAddress));
                    } else {
                        mc.setScreen(new ProfileSelectorScreen(mc.screen, playerUuid, targetAddress));
                    }
                });
            }
            public void notifyHostReady(String playerUuid, TransferSession session) {
                // All client pre-connection work is complete — send READY.
                // Host echoes it back; onReadyEchoReceived triggers the vanilla connect.
                FabricNetworking.sendReadyToServer(session.transferKey);
            }
        });

        // -------------------------------------------------------
        // Inject PortalPacketSender into LeapPortalBlock.
        // Used when a player walks into a portal — sends portal_initiate S→C
        // so the client can start the sequence before any vanilla connect fires.
        // -------------------------------------------------------
        LeapPortalBlock.setPortalPacketSender(FabricNetworking.PORTAL_PACKET_SENDER);

        // Register the LAN status HUD overlay.
        // Renders "[Leap! Pad] Open on port XXXXX" in the top-left corner for OP players.
        // Active only when LAN auto-open has fired. Hides on F3.
        LanStatusHud.register();

        LeapPadCommon.LOGGER.info("Leap! Pad (Fabric client) ready.");
    }
}
