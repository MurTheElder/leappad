package elder.leapp.fabric.network;

// FabricNetworking.java
// Handles all packet sending and receiving using fabric-networking-api-v1.
// Registers channel listeners on init, dispatches received packets to the
// appropriate handlers in common code, wraps outbound sends.
//
// Also implements the TransferOrchestrator.PacketSender and
// AutosavePushManager.DatPusher interfaces so common code can trigger
// network sends without importing Fabric classes directly.

import elder.leapp.LeapPadCommon;
import elder.leapp.network.LeapPadPackets;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.profile.AutosavePushManager;
import elder.leapp.profile.ProfileManager;
import elder.leapp.transfer.TransferOrchestrator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

public class FabricNetworking {

    // -------------------------------------------------------
    // Client-side receivers — registered by LeapPadFabricClient
    // -------------------------------------------------------

    public static void registerClientReceivers() {

        // leappad:probe_response — S→C, Step 3
        // Not used on the game connection — probe travels over raw TCP.
        // Included here for completeness; actual probe response is handled by WorldPinger.

        // leappad:warning_screen — S→C, Step 4
        // Host tells us the target has no Leap! Pad — show the warning screen.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.WARNING_SCREEN,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.WarningScreenPacket pkt = LeapPadPackets.WarningScreenPacket.decode(buf);
                client.execute(() -> {
                    // Open the warning screen on the main thread
                    // Screen implementation is in FabricCommandRegistry / UI layer
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] Warning screen received for target: {}", pkt.targetAddress
                    );
                    // TODO Phase 2 step: wire to actual warning screen GUI
                });
            }
        );

        // leappad:uuid_list — S→C, Step 8
        // Host sends its portal UUID list for deconfliction.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.UUID_LIST,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.UuidListPacket pkt = LeapPadPackets.UuidListPacket.decode(buf);
                String playerUuid = client.player == null ? "" :
                    client.player.getUUID().toString();
                client.execute(() ->
                    TransferOrchestrator.onUuidListReceived(playerUuid, pkt.uuids)
                );
            }
        );

        // leappad:leapforward_cache — S→C, Step 10
        // Host sends a chunk of the Leap! Forward environment package.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.LEAPFORWARD_CACHE,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.LeapForwardCachePacket pkt =
                    LeapPadPackets.LeapForwardCachePacket.decode(buf);
                // Leap! Forward handles its own chunk reassembly via a hook point.
                // Leap! Pad just receives and forwards the chunk data.
                LeapPadCommon.LOGGER.debug(
                    "[Leap! Pad] Leap! Forward cache chunk {}/{} received.",
                    pkt.chunkIndex + 1, pkt.totalChunks
                );
                // Hook point for Leap! Forward — left as log for now
            }
        );

        // leappad:ready_echo — S→C, Step 12
        // Host echoes our READY signal back — dual confirmation, gate releases.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.READY_ECHO,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.ReadyEchoPacket pkt = LeapPadPackets.ReadyEchoPacket.decode(buf);
                String playerUuid = client.player == null ? "" :
                    client.player.getUUID().toString();
                client.execute(() ->
                    TransferOrchestrator.onReadyEchoReceived(playerUuid)
                );
            }
        );

        // leappad:profile_dat_push — S→C, Steps 14+15
        // Host pushes current player dat — save to active profile if one is set.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.PROFILE_DAT_PUSH,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.ProfileDatPushPacket pkt =
                    LeapPadPackets.ProfileDatPushPacket.decode(buf);
                client.execute(() -> ProfileManager.saveReceivedDat(pkt.datBlob));
            }
        );
    }

    // -------------------------------------------------------
    // Server-side receivers — registered by LeapPadFabric
    // -------------------------------------------------------

    public static void registerServerReceivers() {

        // leappad:profile_dat_send — C→S, Step 6
        // Client sends their profile dat blob before vanilla join.
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.PROFILE_DAT_SEND,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.ProfileDatSendPacket pkt =
                    LeapPadPackets.ProfileDatSendPacket.decode(buf);
                server.execute(() -> {
                    // Write the dat blob to playerdata/[UUID].dat
                    // so it is in place when vanilla join completes
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] Profile dat received from {} (profile: {}, LF: {})",
                        player.getName().getString(), pkt.profileUuid, pkt.leapForward
                    );
                    // Actual file write happens in TransferOrchestrator host-side prep
                    // (wired in a later build step)
                });
            }
        );

        // leappad:transfer_cancel — C→S, Step 4
        // Player cancelled the warning screen — discard the session.
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.TRANSFER_CANCEL,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.TransferCancelPacket pkt =
                    LeapPadPackets.TransferCancelPacket.decode(buf);
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] Transfer cancelled by {} (key: {})",
                    player.getName().getString(), pkt.transferKey
                );
                // Session discard handled by TransferOrchestrator
            }
        );

        // leappad:uuid_confirm — C→S, Step 10
        // Client sends the agreed portal UUID after deconfliction.
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.UUID_CONFIRM,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.UuidConfirmPacket pkt =
                    LeapPadPackets.UuidConfirmPacket.decode(buf);
                server.execute(() -> {
                    // Register the agreed UUID on the mirror portal
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] UUID confirmed by {}: {}",
                        player.getName().getString(), pkt.agreedUuid
                    );
                    // Mirror portal UUID finalisation wired in TransferOrchestrator host step
                });
            }
        );

        // leappad:ready — C→S, Step 11
        // Client signals all pre-connection work is complete.
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.READY,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.ReadyPacket pkt = LeapPadPackets.ReadyPacket.decode(buf);
                server.execute(() -> {
                    // Echo the READY signal back to the client
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] READY received from {} — echoing back.",
                        player.getName().getString()
                    );
                    sendReadyEcho(player, pkt.transferKey);
                });
            }
        );
    }

    // -------------------------------------------------------
    // Outbound send helpers — called from common code via interfaces
    // -------------------------------------------------------

    // Sends a profile dat push to the given player (S→C)
    public static void sendProfileDatPush(ServerPlayer player, byte[] datBlob) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ProfileDatPushPacket.encode(
            new LeapPadPackets.ProfileDatPushPacket(datBlob), buf
        );
        ServerPlayNetworking.send(player, LeapPadPackets.PROFILE_DAT_PUSH, buf);
    }

    // Sends the host's portal UUID list to the client for deconfliction (S→C)
    public static void sendUuidList(ServerPlayer player, String[] uuids) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.UuidListPacket.encode(
            new LeapPadPackets.UuidListPacket(uuids), buf
        );
        ServerPlayNetworking.send(player, LeapPadPackets.UUID_LIST, buf);
    }

    // Sends the READY echo back to the client (S→C)
    public static void sendReadyEcho(ServerPlayer player, String transferKey) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ReadyEchoPacket.encode(
            new LeapPadPackets.ReadyEchoPacket(transferKey), buf
        );
        ServerPlayNetworking.send(player, LeapPadPackets.READY_ECHO, buf);
    }

    // Sends a UUID confirm packet to the host (C→S)
    public static void sendUuidConfirmToServer(String agreedUuid) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.UuidConfirmPacket.encode(
            new LeapPadPackets.UuidConfirmPacket(agreedUuid), buf
        );
        ClientPlayNetworking.send(LeapPadPackets.UUID_CONFIRM, buf);
    }

    // Sends a profile dat blob to the host (C→S)
    public static void sendProfileDatToServer(String profileUuid, byte[] datBlob,
                                               boolean leapForward) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ProfileDatSendPacket.encode(
            new LeapPadPackets.ProfileDatSendPacket(profileUuid, datBlob, leapForward), buf
        );
        ClientPlayNetworking.send(LeapPadPackets.PROFILE_DAT_SEND, buf);
    }

    // Sends the READY signal to the host (C→S)
    public static void sendReadyToServer(String transferKey) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ReadyPacket.encode(
            new LeapPadPackets.ReadyPacket(transferKey), buf
        );
        ClientPlayNetworking.send(LeapPadPackets.READY, buf);
    }

    // -------------------------------------------------------
    // Interface implementations — injected into common code at startup
    // -------------------------------------------------------

    // Implements AutosavePushManager.DatPusher
    // Reads the player's current dat from disk and pushes it to the client
    public static final AutosavePushManager.DatPusher DAT_PUSHER = player -> {
        // Read the player's current dat file from the world save
        byte[] datBlob = readPlayerDat(player);
        if (datBlob == null) return;
        sendProfileDatPush(player, datBlob);
    };

    // Reads the raw bytes of a player's .dat file from the world save directory
    private static byte[] readPlayerDat(ServerPlayer player) {
        try {
            java.nio.file.Path datFile = player.getServer()
                .getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat");
            if (!java.nio.file.Files.exists(datFile)) return null;
            return java.nio.file.Files.readAllBytes(datFile);
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Could not read dat file for {}: {}",
                player.getName().getString(), e.getMessage()
            );
            return null;
        }
    }
}
