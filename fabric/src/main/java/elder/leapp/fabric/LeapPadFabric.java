package elder.leapp.fabric;

// LeapPadFabric.java
// The Fabric server-side entry point for Leap! Pad.
// Fabric calls the init() method here when the mod loads on the server side
// (which in a listen server / LAN world is the same process as the client).
//
// Responsibilities:
//   - Call LeapPadCommon.init() to register blocks and items
//   - Start the ProbeListener so incoming transfer probes can be received
//   - Register the ServerPlayConnectionEvents.JOIN listener for bucket assignment
//   - Register Fabric event listeners for world-side logic

import elder.leapp.LeapPadCommon;
import elder.leapp.profile.AutosavePushManager;
import elder.leapp.transfer.ProbeListener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class LeapPadFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Initialize shared content (block + item registration)
        LeapPadCommon.init();

        // Start listening for incoming transfer probes on the configured probe ports.
        // This must start here so the world is reachable before any player tries to connect.
        ProbeListener.start();

        // When a player fully joins the world (after vanilla join completes),
        // assign them to an autosave bucket. This is the ONLY thing we do post-join —
        // all other arrival work completes before vanilla join ever runs.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            AutosavePushManager.assignBucket(handler.getPlayer());
        });

        // When a player disconnects, immediately push their current dat to them
        // so their profile is saved before they leave.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            AutosavePushManager.onPlayerLeave(handler.getPlayer());
        });

        LeapPadCommon.LOGGER.info("Leap! Pad (Fabric server) ready.");
    }
}
