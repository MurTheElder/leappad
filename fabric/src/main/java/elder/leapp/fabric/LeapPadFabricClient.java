package elder.leapp.fabric;

// LeapPadFabricClient.java
// The Fabric client-side entry point for Leap! Pad.
// Fabric calls onInitializeClient() here when the mod loads on the client side.
//
// Responsibilities:
//   - Initialize ProfileManager with resolved paths and Leap! Forward flag (S4)
//   - Register client-side packet receivers via FabricNetworking
//   - Register the portal block render layer (C2)
//   - Inject all four bridge interfaces into TransferOrchestrator (C3):
//       PacketSender     — delegates outbound packets to FabricNetworking
//       GateReleaser     — calls ConnectScreenMixin.releaseGate() (D1-B)
//       PlayerNotifier   — shows messages and screens (stubs for ST1, ST2)
//       PortalConnectTrigger — stores portal context and triggers vanilla connect (D2-B)

import elder.leapp.LeapPadCommon;
import elder.leapp.fabric.mixin.ConnectScreenMixin;
import elder.leapp.fabric.network.FabricNetworking;
import elder.leapp.fabric.registry.FabricRegistrar;
import elder.leapp.profile.ProfileManager;
import elder.leapp.transfer.TransferOrchestrator;
import elder.leapp.transfer.TransferSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class LeapPadFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // S4: Resolve profilesDir and leapForwardPresent here in Fabric-specific code
        // and pass them into ProfileManager.init() rather than letting ProfileManager
        // call FabricLoader directly. This keeps common code loader-agnostic.
        Path profilesDir = FabricLoader.getInstance()
            .getGameDir()
            .resolve("leappad")
            .resolve("profiles");
        boolean leapForwardPresent = FabricLoader.getInstance()
            .isModLoaded("leapforward");
        ProfileManager.init(profilesDir, leapForwardPresent);

        // Register all client-side packet receivers.
        // These handle packets sent from the host world to this client.
        FabricNetworking.registerClientReceivers();

        // C2: Set the portal block render layer to translucent so it renders
        // as a see-through surface rather than a solid opaque cube.
        FabricRegistrar.registerClientSide();

        // -------------------------------------------------------
        // C3: Inject all four bridge interfaces into TransferOrchestrator.
        // These allow common code to trigger Fabric-specific behaviour
        // (packet sends, screen opens, vanilla connect) without importing
        // Fabric classes directly.
        // -------------------------------------------------------

        // PacketSender — delegates all outbound packet calls to FabricNetworking.
        // sendReady is handled via notifyHostReady in PlayerNotifier below.
        TransferOrchestrator.setPacketSender(new TransferOrchestrator.PacketSender() {
            public void sendProfileDat(String playerUuid, String profileUuid,
                                       byte[] datBlob, boolean leapForward) {
                FabricNetworking.sendProfileDatToServer(profileUuid, datBlob, leapForward);
            }
            public void sendUuidConfirm(String playerUuid, String agreedUuid) {
                FabricNetworking.sendUuidConfirmToServer(agreedUuid);
            }
            public void sendReady(String playerUuid, String transferKey) {
                FabricNetworking.sendReadyToServer(transferKey);
            }
        });

        // GateReleaser — calls ConnectScreenMixin.releaseGate(), which arms the
        // bypass flag and re-triggers ConnectScreen.startConnecting() on the render
        // thread using the stored args from the original intercept (D1-B).
        TransferOrchestrator.setGateReleaser(playerUuid ->
            ConnectScreenMixin.releaseGate(playerUuid)
        );

        // PlayerNotifier — shows in-game messages and opens screens.
        // showPortalInactive and showTimeout are message-only; the screen stubs
        // for warning (ST1) and profile selector (ST2) will be filled when those
        // screen classes are built in their own build steps.
        TransferOrchestrator.setPlayerNotifier(new TransferOrchestrator.PlayerNotifier() {
            public void showPortalInactive(String playerUuid) {
                // TODO ST: show "Portal is inactive." message to player in chat overlay
                LeapPadCommon.LOGGER.info("[Leap! Pad] Portal inactive — notify player {}.", playerUuid);
            }
            public void showNoLeapPad(String playerUuid, String targetAddress) {
                // TODO ST1: open WarningScreen — implement when WarningScreen class is built
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] No Leap! Pad on target {} — warning screen stub.", targetAddress
                );
            }
            public void showTimeout(String playerUuid) {
                // TODO ST: show "Connection timed out." message to player in chat overlay
                LeapPadCommon.LOGGER.info("[Leap! Pad] Connection timed out — notify player {}.", playerUuid);
            }
            public void openProfileSelector(String playerUuid) {
                // TODO ST2: open ProfileSelectorScreen — implement when that class is built
                LeapPadCommon.LOGGER.info("[Leap! Pad] Profile selector stub for player {}.", playerUuid);
            }
            public void notifyHostReady(String playerUuid, TransferSession session) {
                // All client pre-connection work is complete — send the READY signal.
                // The host will echo it back, which releases the gate.
                FabricNetworking.sendReadyToServer(session.transferKey);
            }
        });

        // PortalConnectTrigger — called by LeapPortalBlock.entityInside() (via
        // TransferOrchestrator.triggerPortalConnect()) when a player walks into a portal.
        // Stores portal context in ConnectScreenMixin so the mixin can read it on the
        // next intercept, then triggers a vanilla connect on the render thread (D2-B).
        TransferOrchestrator.setPortalConnectTrigger((targetAddress, portalUuid, originAddress) -> {
            // Store portal context — the mixin reads and clears these on intercept
            ConnectScreenMixin.setPendingPortalContext(portalUuid, originAddress);

            // Parse host and port from the target address string
            String host;
            int port;
            try {
                int colon = targetAddress.lastIndexOf(':');
                host = targetAddress.substring(0, colon);
                port = Integer.parseInt(targetAddress.substring(colon + 1));
            } catch (Exception e) {
                LeapPadCommon.LOGGER.error(
                    "[Leap! Pad] PortalConnectTrigger could not parse address: {}", targetAddress
                );
                return;
            }

            ServerAddress addr = new ServerAddress(host, port);
            // In 1.20.1, ServerData takes (String name, String ip, boolean isLan).
            // false = not a LAN connection.
            // ServerData.Type does not exist until a later Minecraft version.
            ServerData serverData = new ServerData("leap", targetAddress, false);

            // Open a ConnectScreen on the render thread with the target address.
            // ConnectScreen.init() calls the private connect() method internally —
            // ConnectScreenMixin intercepts that call, reads the portal context stored
            // above, and drives the full transfer sequence.
            // Constructor: ConnectScreen(Screen parent, Minecraft mc, ServerAddress, ServerData)
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                ConnectScreen screen = new ConnectScreen(mc.screen, mc, addr, serverData);
                mc.setScreen(screen);
            });
        });

        LeapPadCommon.LOGGER.info("Leap! Pad (Fabric client) ready.");
    }
}
