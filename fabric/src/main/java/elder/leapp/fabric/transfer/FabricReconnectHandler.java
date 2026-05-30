package elder.leapp.fabric.transfer;

// FabricReconnectHandler.java
// Implements TransferOrchestrator.VanillaConnectTrigger.
// Called when the full pre-connection sequence is complete.
//
// Responsibilities:
//   1. Disconnect the player cleanly from their current world.
//   2. Call ConnectScreen.startConnecting() to open a new ConnectScreen
//      pointing at the target world and initiate the connection.
//
// startConnecting is public in 1.20.1 under Mojang mappings, so it can be
// called directly without a Mixin invoker. ConnectScreenMixin intercepts it,
// sees the session is COMPLETE, and lets it through — vanilla join runs.
//
// Disconnect:
//   mc.getConnection() returns the active ClientPacketListener.
//   getConnection() on that returns the underlying Connection object.
//   disconnect(Component) closes the connection cleanly.
//   If any of these are null the player is not connected — skip disconnect.

import elder.leapp.LeapPadCommon;
import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

public class FabricReconnectHandler implements TransferOrchestrator.VanillaConnectTrigger {

    public static final FabricReconnectHandler INSTANCE = new FabricReconnectHandler();

    private FabricReconnectHandler() {}

    @Override
    public void connect(String playerUuid, String targetAddress) {
        Minecraft mc = Minecraft.getInstance();

        // All Minecraft client state must be touched from the render thread.
        mc.execute(() -> {
            try {
                // Step 1: Disconnect from the current integrated server.
                if (mc.getConnection() != null &&
                    mc.getConnection().getConnection() != null) {
                    mc.getConnection().getConnection().disconnect(
                        Component.translatable("multiplayer.status.quitting")
                    );
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] Disconnected player {} from current world.", playerUuid
                    );
                }

                // Step 2: Parse the target address.
                String host;
                int port;
                try {
                    int colon = targetAddress.lastIndexOf(':');
                    host = targetAddress.substring(0, colon);
                    port = Integer.parseInt(targetAddress.substring(colon + 1));
                } catch (Exception e) {
                    LeapPadCommon.LOGGER.error(
                        "[Leap! Pad] FabricReconnectHandler: bad address '{}' for player {}",
                        targetAddress, playerUuid
                    );
                    return;
                }

                ServerAddress addr = new ServerAddress(host, port);
                // ServerData(String name, String ip, boolean isLan)
                ServerData serverData = new ServerData("leap", targetAddress, false);

                // Step 3: Call startConnecting() — public static method in 1.20.1.
                // ConnectScreenMixin intercepts this, sees session is COMPLETE,
                // and lets vanilla run.
                // mc.screen is passed as parent — if connection fails Minecraft
                // returns to whatever screen was showing (may be null, which is safe).
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] Calling startConnecting for player {} → {}",
                    playerUuid, targetAddress
                );
                ConnectScreen.startConnecting(mc.screen, mc, addr, serverData, false);

            } catch (Exception e) {
                LeapPadCommon.LOGGER.error(
                    "[Leap! Pad] FabricReconnectHandler.connect() threw for player {}: {}",
                    playerUuid, e.getMessage()
                );
            }
        });
    }
}
