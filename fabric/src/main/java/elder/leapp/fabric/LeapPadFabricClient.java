package elder.leapp.fabric;

// LeapPadFabricClient.java
// The Fabric client-side entry point for Leap! Pad.
//
// Responsibilities:
//   - Initialize ProfileManager with resolved paths and Leap! Forward flag (S4)
//   - Register client-side packet receivers via FabricNetworking
//   - Register the portal block render layer (C2)
//   - Inject bridge interfaces into TransferOrchestrator:
//       PacketSender          — delegates outbound packets to FabricNetworking
//       VanillaConnectTrigger — calls FabricReconnectHandler at sequence end
//       PlayerNotifier        — shows messages and opens screens
//       IpRefreshCallback     — triggers async external IP re-fetch when cache expires (SS8)
//   - Inject PortalPacketSender into LeapPortalBlock
//
// Removed: GateReleaser, PortalConnectTrigger (replaced by VanillaConnectTrigger
// and the portal_initiate packet path).

import elder.leapp.LeapPadCommon;
import elder.leapp.fabric.network.FabricNetworking;
import elder.leapp.fabric.registry.FabricCommandRegistry;
import elder.leapp.fabric.registry.FabricRegistrar;
import elder.leapp.fabric.transfer.FabricReconnectHandler;
import elder.leapp.fabric.ui.LanStatusHud;
import elder.leapp.fabric.ui.PortalProfileSelectorScreen;
import elder.leapp.fabric.ui.ProfileSelectorScreen;
import elder.leapp.fabric.ui.WarningScreen;
import elder.leapp.portal.LeapPortalBlock;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.profile.ProfileManager;
import elder.leapp.transfer.TransferOrchestrator;
import elder.leapp.transfer.TransferSession;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.phys.BlockHitResult;
import net.minecraft.phys.HitResult;

import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class LeapPadFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {

        // SS10: Clear any stale LAN HUD state from a previous session (e.g. after crash).
        // Must run before any world loads.
        LanStatusHud.clear();

        // S4: Resolve profilesDir and leapForwardPresent in Fabric-specific code
        // and pass them into ProfileManager.init() to keep common code loader-agnostic.
        Path profilesDir = FabricLoader.getInstance()
            .getGameDir()
            .resolve("leappad")
            .resolve("profiles");
        boolean leapForwardPresent = FabricLoader.getInstance()
            .isModLoaded("leapforward");
        ProfileManager.init(profilesDir, leapForwardPresent);

        // Register all client-side packet receivers.
        FabricNetworking.registerClientReceivers();

        // C2: Set the portal block render layer to translucent.
        FabricRegistrar.registerClientSide();

        // -------------------------------------------------------
        // Inject bridge interfaces into TransferOrchestrator.
        // -------------------------------------------------------

        // PacketSender — delegates outbound packet calls to FabricNetworking.
        TransferOrchestrator.setPacketSender(new TransferOrchestrator.PacketSender() {
            public void sendProfileDat(String playerUuid, String profileUuid,
                                       byte[] datBlob, boolean leapForward) {
                FabricNetworking.sendProfileDatToServer(profileUuid, datBlob, leapForward);
            }
            public void sendUuidConfirm(String playerUuid, String agreedUuid, String originAddress) {
                FabricNetworking.sendUuidConfirmToServer(agreedUuid, originAddress);
            }
            public void sendReady(String playerUuid, String transferKey) {
                // Not called by the orchestrator — READY is sent via notifyHostReady()
                // in the PlayerNotifier below. This method exists to satisfy the interface.
                FabricNetworking.sendReadyToServer(transferKey);
            }
        });

        // VanillaConnectTrigger — called when the full sequence is complete.
        // FabricReconnectHandler disconnects from the current world and calls
        // ConnectScreen.startConnecting() directly (it is public in 1.20.1).
        // ConnectScreenMixin sees the session is COMPLETE and lets it through.
        TransferOrchestrator.setVanillaConnectTrigger(FabricReconnectHandler.INSTANCE);

        // PlayerNotifier — shows messages and opens screens.
        TransferOrchestrator.setPlayerNotifier(new TransferOrchestrator.PlayerNotifier() {
            public void showPortalInactive(String playerUuid) {
                // Only fires on the portal path — direct connects get vanilla's own error.
                // Wrapped in mc.execute() because this fires from the LeapPad-Probe background thread.
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("[Leap! Pad] Portal is inactive.")
                        );
                    }
                });
            }
            public void showNoLeapPad(String playerUuid, String targetAddress) {
                // Look up the origin portal UUID to find the portal's nickname.
                // Falls back to the raw address if no UUID or nickname is available.
                // Wrapped in mc.execute() because this fires from the LeapPad-Probe background thread.
                String originUuid = TransferOrchestrator.getOriginPortalUuid(playerUuid);
                String displayLabel = targetAddress;
                if (originUuid != null) {
                    PortalRegistry.PortalEntry entry = PortalRegistry.getEntry(originUuid);
                    if (entry != null && entry.nickname != null && !entry.nickname.isEmpty()) {
                        displayLabel = entry.nickname;
                    }
                }
                final String label = displayLabel;
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> mc.setScreen(new WarningScreen(mc.screen, playerUuid, label)));
            }
            public void showTimeout(String playerUuid) {
                // Fires from the timeout scheduler thread — must be marshalled to the render thread.
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("[Leap! Pad] Connection timed out.")
                        );
                    }
                });
            }
            public void openProfileSelector(String playerUuid, boolean isPortalPath, String targetAddress) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                mc.execute(() -> {
                    if (isPortalPath) {
                        mc.setScreen(new PortalProfileSelectorScreen(mc.screen, playerUuid, targetAddress));
                    } else {
                        mc.setScreen(new ProfileSelectorScreen(mc.screen, playerUuid, targetAddress));
                    }
                });
            }
            public void notifyHostReady(String playerUuid, TransferSession session) {
                // All client pre-connection work is complete — send READY.
                // Host echoes it back; onReadyEchoReceived triggers the vanilla connect.
                FabricNetworking.sendReadyToServer(session.transferKey);
            }
        });

        // SS8: Inject IP refresh callback so TransferSequencer can trigger a background
        // re-fetch when the cached external IP has expired. Uses the bridge pattern to
        // keep common code free of fabric-specific imports.
        TransferOrchestrator.setIpRefreshCallback(
            () -> FabricCommandRegistry.fetchAndCacheExternalIpAsync()
        );

        // -------------------------------------------------------
        // Inject PortalPacketSender into LeapPortalBlock.
        // Used when a player walks into a portal — sends portal_initiate S→C
        // so the client can start the sequence before any vanilla connect fires.
        // -------------------------------------------------------
        LeapPortalBlock.setPortalPacketSender(FabricNetworking.PORTAL_PACKET_SENDER);

        // Register the LAN status HUD overlay.
        // Renders "[Leap! Pad] Open on port XXXXX" in the top-left corner for OP players.
        // Active only when LAN auto-open has fired. Hides on F3.
        LanStatusHud.register();

        // /portalid get auto-fill — intercepts the command string client-side before
        // it reaches the server. When the player submits "/portalid get" with no args,
        // the looked-at block's coordinates are appended automatically.
        // If the command already has coordinates, it passes through unchanged.
        // If no block is being looked at, the command is cancelled with a hint message.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (!command.equals("portalid get")) {
                // Has args already (e.g. "portalid get 100 64 -200") — pass through
                if (command.startsWith("portalid get ")) return true;
                // Not our command at all
                return true;
            }
            // No args — try to fill in the looked-at block
            Minecraft mc = Minecraft.getInstance();
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos();
                // Re-send with coordinates appended — return false to cancel the original,
                // then dispatch the rewritten command
                mc.getConnection().sendCommand(
                    "portalid get " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                );
                return false; // Cancel the bare /portalid get
            } else {
                // Not looking at a block — cancel and tell the player
                if (mc.player != null) {
                    mc.player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal(
                            "[Leap! Pad] Look at a portal block first, or provide coordinates."
                        )
                    );
                }
                return false;
            }
        });

        LeapPadCommon.LOGGER.info("Leap! Pad (Fabric client) ready.");
    }
}
