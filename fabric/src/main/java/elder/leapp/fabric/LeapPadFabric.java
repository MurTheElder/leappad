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
//     load portal registry, LAN auto-open if configured, inject HostPrepNotifier,
//     init autosave buckets (C6, C7, F2)
//   - On world stop: push final dat, stop ProbeListener, save portal registry,
//     clear LAN status HUD (N3, F2)
//   - Hook server tick to fire AutosavePushManager.onAutosave() every 6000 ticks
//   - Register the ServerPlayConnectionEvents.JOIN listener for bucket assignment
//   - Register the ServerPlayConnectionEvents.DISCONNECT listener for dat push on leave

import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import elder.leapp.config.WorldLanConfig;
import elder.leapp.fabric.network.FabricNetworking;
import elder.leapp.fabric.registry.FabricCommandRegistry;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.probe.PortBindingCache;
import elder.leapp.profile.AutosavePushManager;
import elder.leapp.transfer.ProbeListener;
import elder.leapp.transfer.TransferOrchestrator;
import elder.leapp.transfer.TransferSessionManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class LeapPadFabric implements ModInitializer {

    // Captured in SERVER_STARTING, read in SERVER_STOPPING.
    // Needed because SERVER_STOPPING lambda doesn't have server.getWorldPath() available
    // in a convenient single-expression form, and we want a clean reference.
    private static Path worldSaveDir;

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

            // SS1 fix: clear any stale sessions from a previous world load in the same JVM.
            // Also resets the timeout scheduler state for a clean start.
            TransferSessionManager.init();

            // C6: Load portal registry from the world save directory.
            // server.getWorldPath(ROOT) returns the level folder itself (e.g.
            // saves/New World/). The registry dat lives inside that folder.
            worldSaveDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath();
            PortalRegistry.load(worldSaveDir);

            // F2: LAN auto-open. Read the per-world LAN config and if configured,
            // dispatch /publish [port] to open the world to LAN automatically,
            // then bind the Leap! Pad probe listener on that port.
            WorldLanConfig lanConfig = WorldLanConfig.load(worldSaveDir);
            if (lanConfig.isConfigured()) {
                int lanPort = lanConfig.lanPort;
                try {
                    server.getCommands().getDispatcher().execute(
                        "publish " + lanPort,
                        server.createCommandSourceStack()
                    );
                    PortBindingCache.bindPort(lanPort);
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] World auto-opened to LAN on port {}.", lanPort
                    );
                    // Broadcast chat notification to all OP players
                    // and activate the client-side HUD overlay.
                    FabricNetworking.broadcastLanOpenToOps(server, lanPort);
                } catch (Exception e) {
                    LeapPadCommon.LOGGER.error(
                        "[Leap! Pad] LAN auto-open failed for port {}: {}", lanPort, e.getMessage()
                    );
                }
            }

            // N4: Inject ServerLevelProvider so TransferOrchestrator.onHostPrepComplete()
            // can pass a ServerLevel to MirrorPortalManager when building the mirror portal.
            TransferOrchestrator.setServerLevelProvider(consumer ->
                consumer.accept(server.overworld())
            );

            // B1+B2 fix: inject HostPrepNotifier now that the server reference
            // is available. This allows TransferOrchestrator.onHostPrepComplete()
            // to look up the player and send them the UUID list (step 8).
            // The lambda captures the server reference so it can resolve
            // player UUIDs to ServerPlayer instances at call time.
            TransferOrchestrator.setHostPrepNotifier((playerUuid, uuids) -> {
                ServerPlayer player = server.getPlayerList().getPlayer(
                    java.util.UUID.fromString(playerUuid)
                );
                if (player != null) {
                    FabricNetworking.sendUuidList(player, uuids);
                } else {
                    LeapPadCommon.LOGGER.warn(
                        "[Leap! Pad] HostPrepNotifier: player {} not found on server.", playerUuid
                    );
                }
            });

            // Init autosave bucket system now that config is loaded
            // (playerDatCyclic determines how many buckets to create).
            AutosavePushManager.init();

            LeapPadCommon.LOGGER.info("[Leap! Pad] World starting — config loaded, registry loaded, probe listener started.");
        });

        // N3 fix: clean shutdown when the world stops.
        // Push all remaining player dat, stop probe listener, save portal registry.
        // Without this, ProbeListener threads linger until JVM exit, the final
        // autosave push never fires, and any portal registry changes made in the
        // session since the last disk write are lost.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // SS9: use server-aware shutdown to cross-reference live players
            AutosavePushManager.onShutdown(server);
            ProbeListener.stop();
            // SS3+SS4 fix: clear cooldowns and shut down the timeout scheduler cleanly.
            TransferSessionManager.shutdown();
            if (worldSaveDir != null) {
                // SS6: promote any unfinished provisional mirror portals before saving
                PortalRegistry.promotePendingMirrorPortals();
                PortalRegistry.save();
            }
            // Clear the LAN status HUD overlay on the client side
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> elder.leapp.fabric.ui.LanStatusHud.clear());
            }
            LeapPadCommon.LOGGER.info("[Leap! Pad] World stopping — shutdown complete.");
        });

        // Hook the autosave cycle so AutosavePushManager can push player dat on schedule.
        // Minecraft autosaves every 6000 ticks (5 minutes) by default.
        // We fire onAutosave() at the same interval by checking server.getTickCount().
        // This must be registered here (not inside SERVER_STARTING) so the listener
        // is active for the entire server lifetime, not just after first world start.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 6000 == 0) {
                AutosavePushManager.onAutosave();
            }
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
