package elder.leapp.fabric.mixin;

// ConnectScreenMixin.java
// Injects into ConnectScreen.startConnecting() to intercept direct connect
// and server list connection attempts before vanilla runs.
//
// startConnecting is the Mojang-mapped name for method_36877 — the public static
// factory that opens a ConnectScreen and initiates a multiplayer connection.
//
// Flow:
//   1. Player clicks join — vanilla calls startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean)
//   2. This mixin fires at HEAD, cancels the call, and passes the address to
//      TransferOrchestrator.onConnectionAttempt() on a background thread.
//   3. The orchestrator runs the full pre-connection sequence (probe, profile selector,
//      dat send, host prep, UUID deconfliction, READY handshake).
//   4. When complete, FabricReconnectHandler calls startConnecting() again via
//      ConnectScreenInvoker.invokeStartConnecting().
//   5. This mixin fires again. TransferOrchestrator.isSessionComplete() returns true —
//      the mixin does NOT cancel. Vanilla connect runs normally.
//   6. TransferOrchestrator.onVanillaConnectCompleted() clears the session.
//
// Portal path:
//   Portal walk-in sends leappad:portal_initiate S→C → client calls
//   onConnectionAttempt() directly. The sequence runs. FabricReconnectHandler
//   calls startConnecting() at the end, which fires this mixin at step 4.
//   Session is COMPLETE — mixin lets it through.

import elder.leapp.LeapPadCommon;
import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    // Mixin target string from confirmed Mojang mapping:
    // Lnet/minecraft/client/gui/screens/ConnectScreen;startConnecting(...)V
    @Inject(
        method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;" +
                 "Lnet/minecraft/client/Minecraft;" +
                 "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;" +
                 "Lnet/minecraft/client/multiplayer/ServerData;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void leappad_interceptStartConnecting(Screen parent,
                                                          Minecraft minecraft,
                                                          ServerAddress address,
                                                          ServerData serverData,
                                                          boolean isQuickPlay,
                                                          CallbackInfo ci) {

        // No player present — early launch call before the player exists.
        // Let vanilla run normally.
        if (minecraft.player == null) return;

        String playerUuid = minecraft.player.getUUID().toString();

        // Check if the sequence is already complete for this player.
        // This is the re-trigger from FabricReconnectHandler — let it through
        // and clear the session.
        if (TransferOrchestrator.isSessionComplete(playerUuid)) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Session complete for {} — passing startConnecting through.",
                playerUuid
            );
            TransferOrchestrator.onVanillaConnectCompleted(playerUuid);
            return; // Do NOT cancel — vanilla connect runs
        }

        // First intercept — cancel vanilla and start our sequence.
        ci.cancel();

        String targetAddress = address.getHost() + ":" + address.getPort();
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Intercepted startConnecting for {} → {} — starting sequence.",
            playerUuid, targetAddress
        );

        // Start the sequence on a background thread.
        // null origin args = not a portal path.
        final String capturedTarget = targetAddress;
        final String capturedUuid   = playerUuid;
        Thread t = new Thread(
            () -> TransferOrchestrator.onConnectionAttempt(
                capturedUuid, capturedTarget, null, null
            ),
            "LeapPad-ConnectEntry"
        );
        t.setDaemon(true);
        t.start();
    }
}
