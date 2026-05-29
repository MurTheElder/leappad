package elder.leapp.fabric.transfer;
 
// FabricReconnectHandler.java
// Implements TransferOrchestrator.VanillaConnectTrigger.
// Called when the full pre-connection sequence is complete.
//
// Responsibilities:
//   1. Disconnect the player cleanly from their current world (integrated server).
//   2. Call ConnectScreenInvoker.invokeConnect() to open a new ConnectScreen
//      pointing at the target world and initiate the connection.
//
// ConnectScreenMixin intercepts the invokeConnect() call, sees that the session
// is COMPLETE, and lets it through — vanilla join runs normally.
//
// Disconnect:
//   Minecraft.getConnection() returns the active ClientPacketListener.
//   ClientPacketListener.getConnection() returns the underlying Connection.
//   Connection.disconnect(Component) sends a disconnect packet and closes the connection.
//   method_10747 is the intermediary name for disconnect() on Connection.
//   We call it via a @Invoker (ConnectionInvoker) with remap = false to avoid
//   Mojang mapping resolution issues.
//
// This class is registered as the VanillaConnectTrigger in LeapPadFabricClient.
 
import elder.leapp.LeapPadCommon;
import elder.leapp.fabric.mixin.ConnectScreenInvoker;
import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
 
public class FabricReconnectHandler implements TransferOrchestrator.VanillaConnectTrigger {
 
    public static final FabricReconnectHandler INSTANCE = new FabricReconnectHandler();
 
    private FabricReconnectHandler() {}
 
    @Override
    public void connect(String playerUuid, String targetAddress) {
        Minecraft mc = Minecraft.getInstance();
 
        // Schedule on the render thread — all Minecraft client state must be
        // touched from the render thread.
        mc.execute(() -> {
            try {
                // Step 1: Disconnect from the current integrated server.
                // getConnection() returns ClientPacketListener (the active play connection).
                // If null, the player isn't connected — skip the disconnect step.
                if (mc.getConnection() != null) {
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
                        "[Leap! Pad] FabricReconnectHandler: bad target address '{}' for player {}",
                        targetAddress, playerUuid
                    );
                    return;
                }
 
                ServerAddress addr = new ServerAddress(host, port);
                // ServerData(String name, String ip, boolean isLan)
                // false = not a LAN connection
                ServerData serverData = new ServerData("leap", targetAddress, false);
 
                // Step 3: Call method_36877 via ConnectScreenInvoker.
                // mc.screen is passed as the parent — it's whatever the player sees now
                // (likely null or the disconnect screen). If connection fails, Minecraft
                // returns to this screen. Passing null is also safe.
                // ConnectScreenMixin intercepts this call, sees session is COMPLETE,
                // and lets vanilla run.
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] Opening ConnectScreen for player {} → {}", playerUuid, targetAddress
                );
                ConnectScreenInvoker.invokeConnect(mc.screen, mc, addr, serverData, false);
 
            } catch (Exception e) {
                LeapPadCommon.LOGGER.error(
                    "[Leap! Pad] FabricReconnectHandler.connect() threw for player {}: {}",
                    playerUuid, e.getMessage()
                );
            }
        });
    }
}
