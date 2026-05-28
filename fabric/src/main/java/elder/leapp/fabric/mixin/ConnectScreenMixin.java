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
//
// Gate release (D1-B):
//   On first intercept, args are stored in leappad_pendingArgs.
//   When the orchestrator finishes all pre-connection work, it calls releaseGate().
//   releaseGate() sets leappad_bypassGate = true, then re-calls ConnectScreen.connect()
//   using the stored args, scheduled on the render thread.
//   On that second intercept, the mixin sees leappad_bypassGate, clears it, and
//   does not cancel — vanilla connect runs normally.
//
// Portal context (D2-B):
//   LeapPortalBlock does not call the orchestrator directly. Instead it calls
//   TransferOrchestrator.triggerPortalConnect(), which (via the PortalConnectTrigger
//   bridge) calls setPendingPortalContext() here and then triggers a vanilla connect.
//   On intercept, the mixin reads and clears the portal context fields and passes them
//   to onConnectionAttempt(). For non-portal connections (direct connect, server list,
//   LAN), these fields are null and behaviour is identical to before.

import elder.leapp.LeapPadCommon;
import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    // -------------------------------------------------------
    // Static state — gate release (D1-B)
    // -------------------------------------------------------

    // Stores the ServerAddress and ServerData from the intercepted call,
    // keyed by player UUID. Held until releaseGate() fires, then used
    // to re-trigger the vanilla connect.
    private static final Map<String, Object[]> leappad_pendingArgs = new ConcurrentHashMap<>();

    // When true, the next intercept bypasses all Leap! Pad logic and lets vanilla run.
    // Set from a background thread by releaseGate(); read from the render thread in the
    // injection. Must be volatile to guarantee immediate visibility across threads.
    private static volatile boolean leappad_bypassGate = false;

    // -------------------------------------------------------
    // Static state — portal context (D2-B)
    // -------------------------------------------------------

    // Set by setPendingPortalContext() (called from the PortalConnectTrigger bridge)
    // before a portal-triggered vanilla connect. Read and cleared on the next intercept.
    // volatile — written from the server thread (entity tick), read from the render thread.
    private static volatile String leappad_pendingPortalUuid = null;
    private static volatile String leappad_pendingOriginAddress = null;

    // -------------------------------------------------------
    // Static methods — called by bridge implementations in LeapPadFabricClient
    // -------------------------------------------------------

    // Called by the GateReleaser bridge when the orchestrator finishes all
    // pre-connection work and the player is ready to join.
    // Retrieves stored connect args, arms the bypass flag, and schedules
    // the vanilla connect re-trigger on the render thread.
    public static void releaseGate(String playerUuid) {
        Object[] args = leappad_pendingArgs.remove(playerUuid);
        if (args == null) {
            // This would mean releaseGate was called with no matching stored session.
            // Log it so we can diagnose if it ever happens.
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] releaseGate called for player {} but no pending connect args found.",
                playerUuid
            );
            return;
        }

        ServerAddress address   = (ServerAddress) args[0];
        ServerData    serverData = (ServerData)    args[1];

        // Arm the bypass flag before scheduling — the render thread may pick this up
        // immediately on the next frame.
        leappad_bypassGate = true;

        // Schedule the vanilla connect re-trigger on the render thread.
        // ConnectScreen.connect(Minecraft, ServerAddress, ServerData) is the public
        // static 3-param entry point in 1.20.1. It constructs a ConnectScreen internally
        // and calls the private instance connect() method that our mixin intercepts.
        // The bypass flag ensures the mixin lets it through on this second call.
        Minecraft mc = Minecraft.getInstance();
        final ServerAddress finalAddress = address;
        final ServerData finalServerData = serverData;
        mc.execute(() ->
            ConnectScreen.connect(mc, finalAddress, finalServerData)
        );

        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Gate released for player {} — vanilla connect re-triggered.", playerUuid
        );
    }

    // Called by the PortalConnectTrigger bridge (injected from LeapPadFabricClient)
    // before it triggers a vanilla connect on behalf of a portal walk-in.
    // The next intercept will read these fields, clear them, and pass them to
    // onConnectionAttempt() as portal context.
    public static void setPendingPortalContext(String portalUuid, String originAddress) {
        leappad_pendingPortalUuid   = portalUuid;
        leappad_pendingOriginAddress = originAddress;
    }

    // -------------------------------------------------------
    // Mixin injection — the gate itself
    // -------------------------------------------------------

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

        // D1-B: If the bypass flag is set, this is the gate-release re-trigger.
        // Clear the flag and return without cancelling — vanilla connect runs.
        if (leappad_bypassGate) {
            leappad_bypassGate = false;
            LeapPadCommon.LOGGER.info("[Leap! Pad] Bypass active — vanilla connect proceeding.");
            return;
        }

        // All other paths: cancel vanilla and drive the sequence through the orchestrator.
        String targetAddress = address.getHost() + ":" + address.getPort();
        String playerUuid    = minecraft.getUser().getProfileId().toString();

        // Store the connect args so releaseGate() can re-trigger vanilla connect later.
        leappad_pendingArgs.put(playerUuid, new Object[]{address, serverData});

        // D2-B: Read and clear portal context. Null for non-portal connections
        // (direct connect, server list, LAN) — those paths never call setPendingPortalContext().
        String portalUuid    = leappad_pendingPortalUuid;
        String originAddress = leappad_pendingOriginAddress;
        leappad_pendingPortalUuid    = null;
        leappad_pendingOriginAddress = null;

        // Cancel vanilla connection — TransferOrchestrator drives everything from here.
        // The gate will not release until onReadyEchoReceived() fires and calls releaseGate().
        ci.cancel();

        TransferOrchestrator.onConnectionAttempt(
            playerUuid,
            targetAddress,
            portalUuid,    // null for direct connect / server list / LAN
            originAddress  // null for direct connect / server list / LAN
        );
    }
}
