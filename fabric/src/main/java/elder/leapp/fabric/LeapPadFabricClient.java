package elder.leapp.fabric;

// LeapPadFabricClient.java
// The Fabric client-side entry point for Leap! Pad.
// Fabric calls the onInitializeClient() method here when the mod loads
// on the client side.
//
// Responsibilities:
//   - Register client-side packet receivers via FabricNetworking
//   - Initialize ProfileManager so profile data is ready before any connection
//   - Detect and cache the Leap! Forward presence flag at launch

import elder.leapp.LeapPadCommon;
import elder.leapp.fabric.network.FabricNetworking;
import elder.leapp.profile.ProfileManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public class LeapPadFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Initialize the profile manager — loads any saved profiles from disk
        // so they are ready to display in the selector before the player connects anywhere
        ProfileManager.init();

        // Register all client-side packet receivers.
        // These are the handlers for packets sent from the host world to this client.
        FabricNetworking.registerClientReceivers();

        LeapPadCommon.LOGGER.info("Leap! Pad (Fabric client) ready.");
    }
}
