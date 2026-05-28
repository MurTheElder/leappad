package elder.leapp.fabric;

// LeapPadFabric.java
// The Fabric server-side entry point for Leap! Pad.
// Fabric calls onInitialize() here when the mod loads on the server side
// (which in a listen server / LAN world is the same process as the client).
//
// Responsibilities:
//   - Call LeapPadCommon.init() to register blocks and items
//   - Register /leappad and /portalid commands (C5)
//   - Register server-side packet receivers (C1)
//   - Inject the AutosavePushManager.DatPusher bridge (C4)
//   - On world start: load config, init port cache, start ProbeListener,
//     load portal registry, init autosave buckets (C6, C7)
//   - Register the ServerPlayConnectionEvents.JOIN listener for bucket assignment
//   - Register the ServerPlayConnectionEvents.DISCONNECT listener for dat push on leave

import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import elder.leapp.fabric.network.FabricNetworking;
import elder.leapp.fabric.registry.FabricCommandRegistry;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.probe.PortBindingCache;
import elder.leapp.profile.AutosavePushManager;
import elder.leapp.transfer.ProbeListener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class LeapPadFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Initialize shared content (block + item registration)
        LeapPadCommon.init();

        // C5: Register /leappad and /portalid commands.
        // Must be called here so the command registration callback fires during
        // mod init, before the world starts.
        FabricCommandRegistry.register();

        // C1: Register server-side packet receivers so the host can receive
        // profile_dat_send, transfer_cancel, uuid_confirm, and ready packets.
        FabricNetworking.registerServerReceivers();

        // C4: Inject the DatPusher bridge so AutosavePushManager can send
        // profile dat to players on autosave cycles and on disconnect.
        AutosavePushManager.setDatPusher(FabricNetworking.DAT_PUSHER);

        // C6 + C7: Load config, init port binding cache, load portal registry, and
        // start the probe listener when the world starts. SERVER_STARTING fires before
        // any player can connect, so all config values and port bindings are ready
        // before ProbeListener opens any sockets.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // C6: Resolve and load config from .minecraft/config/leappad.json.
            // FabricLoader.getConfigDir() always points to .minecraft/config/
            // regardless of whether this is a listen server or dedicated server.
            Path configDir = FabricLoader.getInstance().getConfigDir();
            LeapPadConfig.load(configDir);

            // C7: Init port binding cache with the actual game port now that
            // config is loaded and the server port is known.
            PortBindingCache.init(server.getPort());

            // Start listening for incoming transfer probes on the configured probe ports.
            // Must be called AFTER PortBindingCache.init() so the listener binds the
            // correct ports. Starting before init() would use port 0 as the game port.
            ProbeListener.start();

            // C6: Load portal registry from the world save directory.
            // server.getWorldPath(ROOT) returns the level folder itself (e.g.
            // saves/New World/). The registry dat lives inside that folder.
            Path worldSaveDir = server.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath();
            PortalRegistry.load(worldSaveDir);

            // Init autosave bucket system now that config is loaded
            // (playerDatCyclic determines how many buckets to create).
            AutosavePushManager.init();

            LeapPadCommon.LOGGER.info("[Leap! Pad] World starting — config loaded, registry loaded, probe listener started.");
        });

        // When a player fully joins the world (after vanilla join completes),
        // assign them to an autosave bucket. This is the ONLY post-join action —
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
