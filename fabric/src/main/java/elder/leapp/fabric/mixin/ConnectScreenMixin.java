package elder.leapp.fabric.mixin;

// ConnectScreenMixin.java
// Injects into method_36877 (ConnectScreen's static connect factory) to intercept
// direct connect and server list connection attempts before vanilla runs.
//
// Flow:
//   1. Player clicks join — vanilla calls method_36877(Screen, Minecraft, ServerAddress, ServerData, boolean)
//   2. This mixin fires at HEAD, cancels the call, and passes the address/serverData
//      to TransferOrchestrator.onConnectionAttempt() on a background thread.
//   3. The orchestrator runs the full pre-connection sequence (probe, profile selector,
//      dat send, host prep, UUID deconfliction, READY handshake).
//   4. When complete, FabricReconnectHandler.connect() disconnects from the current
//      world and calls method_36877 again via ConnectScreenInvoker.
//   5. This mixin fires again. This time TransferOrchestrator.isSessionComplete()
//      returns true — the mixin does NOT cancel, and vanilla connect runs normally.
//   6. TransferOrchestrator.onVanillaConnectCompleted() clears the session.
//
// Portal path:
//   The portal path does NOT go through this mixin on the initial trigger.
//   LeapPortalBlock sends a leappad:portal_initiate packet → client calls
//   onConnectionAttempt() directly. The sequence runs. FabricReconnectHandler
//   calls method_36877 at the end, which fires this mixin at step 4 above.
//   At that point the session is COMPLETE and the mixin lets it through.
//
// remap = false: method_36877 is referenced by intermediary name to avoid
// Mojang mapping resolution issues.

import elder.leapp.LeapPadCommon;
import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.screens.ConnectScreen;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    // Inject at the HEAD of method_36877 — the static factory that opens
    // ConnectScreen and initiates the connection. remap = false so Mixin
    // resolves by intermediary name rather than Mojang-mapped name.
    @Inject(
        method = "method_36877(Lnet/minecraft/class_437;Lnet/minecraft/class_310;" +
                 "Lnet/minecraft/class_639;Lnet/minecraft/class_642;Z)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private static void leappad_interceptConnect(Screen parent, Minecraft minecraft,
                                                  ServerAddress address, ServerData serverData,
                                                  boolean quickPlay, CallbackInfo ci) {

        // Identify the player. On a listen server / integrated server context,
        // minecraft.player is the local player.
        if (minecraft.player == null) {
            // No player present — this is an early-launch call before the player exists.
            // Let vanilla run normally.
            return;
        }

        String playerUuid = minecraft.player.getUUID().toString();

        // Check if the sequence is already complete for this player.
        // This happens when FabricReconnectHandler calls method_36877 after all
        // pre-arrival work is done. Let the call through and clear the session.
        if (TransferOrchestrator.isSessionComplete(playerUuid)) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Session complete for player {} — passing vanilla connect through.",
                playerUuid
            );
            TransferOrchestrator.onVanillaConnectCompleted(playerUuid);
            return; // Do NOT cancel — vanilla connect runs
        }

        // First intercept — cancel vanilla and start our sequence.
        ci.cancel();

        String targetAddress = address.getHost() + ":" + address.getPort();
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Intercepted connect for player {} → {} — starting sequence.",
            playerUuid, targetAddress
        );

        // Start the sequence on a background thread.
        // null origin args = not a portal path (portal path enters via portal_initiate packet).
        final String capturedTarget = targetAddress;
        final String capturedUuid   = playerUuid;
        Thread t = new Thread(
            () -> TransferOrchestrator.onConnectionAttempt(capturedUuid, capturedTarget, null, null),
            "LeapPad-ConnectEntry"
        );
        t.setDaemon(true);
        t.start();
    }
}
