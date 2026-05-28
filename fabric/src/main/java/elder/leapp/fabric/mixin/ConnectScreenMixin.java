package elder.leapp.fabric.mixin;

// ConnectScreenMixin.java
// The pre-connection gate for Leap! Pad.
// Targets vanilla's ConnectScreen — the screen shown when Minecraft is
// in the process of connecting to a multiplayer world.
//
// This Mixin intercepts the connection attempt at the earliest possible point,
// before anything vanilla runs, and hands off to TransferOrchestrator.
// It makes NO routing decisions itself — all logic lives in the orchestrator.
//
// This is the single hook point that all connection types go through:
// portal walk-in, direct connect, server list click, and LAN join.
// Leap! Forward and Leap! Backwards also register their hooks here
// rather than writing their own Mixins.

import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    // Injects at the very start of the connect() method — before any vanilla
    // connection logic runs. The CallbackInfo allows us to cancel vanilla execution.
    @Inject(
        method = "connect(Lnet/minecraft/client/Minecraft;" +
                 "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;" +
                 "Lnet/minecraft/client/multiplayer/ServerData;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void leappad_interceptConnect(Minecraft minecraft,
                                           ServerAddress address,
                                           ServerData serverData,
                                           CallbackInfo ci) {
        // Build the target address string in host:port format
        String targetAddress = address.getHost() + ":" + address.getPort();

        // Get the player UUID — available from the local game profile
        String playerUuid = minecraft.getUser().getProfileId().toString();

        // Cancel vanilla connection — TransferOrchestrator takes it from here.
        // The orchestrator will run the probe, drive the sequence,
        // and release the gate (allowing vanilla connect to proceed) only when ready.
        ci.cancel();

        // Hand off to the orchestrator.
        // originPortalUuid and originAddress are null here — LeapPortalBlock.entityInside()
        // calls onConnectionAttempt() directly with portal context when it's a portal walk-in.
        // Direct connect, server list, and LAN joins come through here without portal context.
        TransferOrchestrator.onConnectionAttempt(
            playerUuid,
            targetAddress,
            null,  // no origin portal UUID — not a portal path
            null   // no origin address — not a portal path
        );
    }
}
