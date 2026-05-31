package elder.leapp.fabric.network;

// FabricNetworking.java
// Handles all packet sending and receiving using fabric-networking-api-v1.
// Registers channel listeners on init, dispatches received packets to the
// appropriate handlers in common code, wraps outbound sends.
//
// Implements:
//   - TransferOrchestrator.PacketSender
//   - AutosavePushManager.DatPusher
//   - LeapPortalBlock.PortalPacketSender (new — sends portal_initiate S→C)
//
// Changes from Phase 2:
//   - Added leappad:portal_initiate client-side receiver (portal path entry).
//   - Added sendPortalInitiate() outbound helper for LeapPortalBlock.
//   - ST3: PROFILE_DAT_SEND receiver writes blob to playerdata/[UUID].dat.
//   - ST4: UUID_CONFIRM receiver calls PortalRegistry.updateMirrorPortalUuid().
//   - B1+B2 fix: PROFILE_DAT_SEND receiver now calls
//     TransferOrchestrator.onHostPrepComplete() after writing the dat file.
//     This triggers the host to send the UUID list to the client (step 8),
//     completing the host prep phase the sequence was previously skipping.
//   - B1+B2 fix: HOST_PREP_NOTIFIER static instance added — implements
//     TransferOrchestrator.HostPrepNotifier using sendUuidList().

import elder.leapp.LeapPadCommon;
import elder.leapp.network.LeapPadPackets;
import elder.leapp.portal.LeapPortalBlock;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.profile.AutosavePushManager;
import elder.leapp.profile.ProfileManager;
import elder.leapp.transfer.TransferOrchestrator;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricNetworking {

    // -------------------------------------------------------
    // Client-side receivers
    // -------------------------------------------------------

    public static void registerClientReceivers() {

        // leappad:warning_screen — S→C, Step 4
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.WARNING_SCREEN,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.WarningScreenPacket pkt =
                    LeapPadPackets.WarningScreenPacket.decode(buf);
                client.execute(() -> {
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] Warning screen: target has no Leap! Pad — {}",
                        pkt.targetAddress
                    );
                    // TODO ST1: open WarningScreen
                });
            }
        );

        // leappad:uuid_list — S→C, Step 8
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
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.LEAPFORWARD_CACHE,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.LeapForwardCachePacket pkt =
                    LeapPadPackets.LeapForwardCachePacket.decode(buf);
                client.execute(() ->
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] Leap! Forward cache chunk {}/{} received.",
                        pkt.chunkIndex + 1, pkt.totalChunks
                    )
                );
            }
        );

        // leappad:ready_echo — S→C, Step 12
        // Host confirms all pre-arrival work is done. Trigger vanilla connect.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.READY_ECHO,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.ReadyEchoPacket pkt = LeapPadPackets.ReadyEchoPacket.decode(buf);
                String playerUuid = client.player == null ? "" :
                    client.player.getUUID().toString();
                client.execute(() -> {
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] READY echo received for player {} (key: {})",
                        playerUuid, pkt.transferKey
                    );
                    TransferOrchestrator.onReadyEchoReceived(playerUuid);
                });
            }
        );

        // leappad:profile_dat_push — S→C, Steps 14+15
        // Host pushes current player dat on autosave or disconnect.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.PROFILE_DAT_PUSH,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.ProfileDatPushPacket pkt =
                    LeapPadPackets.ProfileDatPushPacket.decode(buf);
                client.execute(() -> {
                    if (ProfileManager.getActiveProfileUuid() != null) {
                        ProfileManager.saveReceivedDat(pkt.datBlob);
                    }
                });
            }
        );

        // leappad:portal_initiate — S→C, Portal path entry
        // Sent by LeapPortalBlock when a player walks into a linked portal.
        // Calls onConnectionAttempt() directly — no vanilla connect trigger needed here.
        // The full sequence runs, and FabricReconnectHandler calls method_36877 at the end.
        ClientPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.PORTAL_INITIATE,
            (client, handler, buf, responseSender) -> {
                LeapPadPackets.PortalInitiatePacket pkt =
                    LeapPadPackets.PortalInitiatePacket.decode(buf);
                String playerUuid = client.player == null ? "" :
                    client.player.getUUID().toString();
                client.execute(() -> {
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] portal_initiate received for player {} → {}",
                        playerUuid, pkt.targetAddress
                    );
                    // Start the sequence directly — this is the portal path entry point.
                    // originPortalUuid and originAddress are provided so the sequence
                    // knows this is a portal path and can build the mirror portal.
                    TransferOrchestrator.onConnectionAttempt(
                        playerUuid,
                        pkt.targetAddress,
                        pkt.portalUuid,
                        pkt.originAddress
                    );
                });
            }
        );
    }

    // -------------------------------------------------------
    // Server-side receivers
    // -------------------------------------------------------

    public static void registerServerReceivers() {

        // leappad:profile_dat_send — C→S, Step 6
        // ST3: Write the blob to playerdata/[UUID].dat immediately so it is
        // in place when vanilla join completes and the player spawns.
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.PROFILE_DAT_SEND,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.ProfileDatSendPacket pkt =
                    LeapPadPackets.ProfileDatSendPacket.decode(buf);
                server.execute(() -> {
                    // Only write the dat file if a real blob was sent.
                    // An empty blob means the player has no active profile (N1 fix) —
                    // the packet was sent purely to trigger onHostPrepComplete().
                    if (pkt.datBlob.length > 0) {
                        try {
                            Path datFile = player.getServer()
                                .getWorldPath(LevelResource.PLAYER_DATA_DIR)
                                .resolve(player.getStringUUID() + ".dat");
                            Files.createDirectories(datFile.getParent());
                            Files.write(datFile, pkt.datBlob);
                            LeapPadCommon.LOGGER.info(
                                "[Leap! Pad] Profile dat written for {} — profile: {}, {} bytes, LF: {}",
                                player.getName().getString(),
                                pkt.profileUuid, pkt.datBlob.length, pkt.leapForward
                            );
                        } catch (IOException e) {
                            LeapPadCommon.LOGGER.error(
                                "[Leap! Pad] Failed to write profile dat for {}: {}",
                                player.getName().getString(), e.getMessage()
                            );
                        }
                    } else {
                        LeapPadCommon.LOGGER.info(
                            "[Leap! Pad] Empty profile dat received for {} — no file written (no active profile).",
                            player.getName().getString()
                        );
                    }

                    // Trigger host prep completion (step 7 → step 8).
                    // Fires for all PROFILE_DAT_SEND receipts — onHostPrepComplete()
                    // checks session.isPortalPath and is a no-op on the non-portal path.
                    TransferOrchestrator.onHostPrepComplete(player.getStringUUID());
                });
            }
        );

        // leappad:transfer_cancel — C→S, Step 4
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.TRANSFER_CANCEL,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.TransferCancelPacket pkt =
                    LeapPadPackets.TransferCancelPacket.decode(buf);
                LeapPadCommon.LOGGER.info(
                    "[Leap! Pad] Transfer cancelled by {} (key: {})",
                    player.getName().getString(), pkt.transferKey
                );
            }
        );

        // leappad:uuid_confirm — C→S, Step 10
        // Finalises the mirror portal: renames provisional UUID to agreed UUID,
        // links the portal to the origin address, and updates the player's spawn point.
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.UUID_CONFIRM,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.UuidConfirmPacket pkt =
                    LeapPadPackets.UuidConfirmPacket.decode(buf);
                server.execute(() -> {
                    // Rename provisional UUID → agreed UUID in the registry.
                    PortalRegistry.updateMirrorPortalUuid(
                        player.getStringUUID(), pkt.agreedUuid
                    );
                    // N4: Link the (now correctly named) mirror portal back to
                    // the origin world address so players can use it to return.
                    if (!pkt.originAddress.isEmpty()) {
                        PortalRegistry.linkPortal(pkt.agreedUuid, pkt.originAddress);
                    }
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] Mirror portal finalised for {}: UUID={}, linked to {}",
                        player.getName().getString(), pkt.agreedUuid, pkt.originAddress
                    );
                });
            }
        );

        // leappad:ready — C→S, Step 11
        ServerPlayNetworking.registerGlobalReceiver(
            LeapPadPackets.READY,
            (server, player, handler, buf, responseSender) -> {
                LeapPadPackets.ReadyPacket pkt = LeapPadPackets.ReadyPacket.decode(buf);
                server.execute(() -> {
                    LeapPadCommon.LOGGER.info(
                        "[Leap! Pad] READY from {} — echoing back.", player.getName().getString()
                    );
                    sendReadyEcho(player, pkt.transferKey);
                });
            }
        );
    }

    // -------------------------------------------------------
    // Outbound send helpers
    // -------------------------------------------------------

    public static void sendProfileDatPush(ServerPlayer player, byte[] datBlob) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ProfileDatPushPacket.encode(
            new LeapPadPackets.ProfileDatPushPacket(datBlob), buf
        );
        ServerPlayNetworking.send(player, LeapPadPackets.PROFILE_DAT_PUSH, buf);
    }

    public static void sendUuidList(ServerPlayer player, String[] uuids) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.UuidListPacket.encode(
            new LeapPadPackets.UuidListPacket(uuids), buf
        );
        ServerPlayNetworking.send(player, LeapPadPackets.UUID_LIST, buf);
    }

    public static void sendReadyEcho(ServerPlayer player, String transferKey) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ReadyEchoPacket.encode(
            new LeapPadPackets.ReadyEchoPacket(transferKey), buf
        );
        ServerPlayNetworking.send(player, LeapPadPackets.READY_ECHO, buf);
    }

    public static void sendUuidConfirmToServer(String agreedUuid, String originAddress) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.UuidConfirmPacket.encode(
            new LeapPadPackets.UuidConfirmPacket(agreedUuid, originAddress), buf
        );
        ClientPlayNetworking.send(LeapPadPackets.UUID_CONFIRM, buf);
    }

    public static void sendProfileDatToServer(String profileUuid, byte[] datBlob,
                                               boolean leapForward) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ProfileDatSendPacket.encode(
            new LeapPadPackets.ProfileDatSendPacket(profileUuid, datBlob, leapForward), buf
        );
        ClientPlayNetworking.send(LeapPadPackets.PROFILE_DAT_SEND, buf);
    }

    public static void sendReadyToServer(String transferKey) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.ReadyPacket.encode(
            new LeapPadPackets.ReadyPacket(transferKey), buf
        );
        ClientPlayNetworking.send(LeapPadPackets.READY, buf);
    }

    // Sends portal_initiate to the player's client (S→C)
    public static void sendPortalInitiate(ServerPlayer player, String targetAddress,
                                           String portalUuid, String originAddress) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        LeapPadPackets.PortalInitiatePacket.encode(
            new LeapPadPackets.PortalInitiatePacket(targetAddress, portalUuid, originAddress), buf
        );
        ServerPlayNetworking.send(player, LeapPadPackets.PORTAL_INITIATE, buf);
    }

    // -------------------------------------------------------
    // Interface implementations injected into common code at startup
    // -------------------------------------------------------

    // Implements AutosavePushManager.DatPusher
    public static final AutosavePushManager.DatPusher DAT_PUSHER = player -> {
        byte[] datBlob = readPlayerDat(player);
        if (datBlob == null) return;
        sendProfileDatPush(player, datBlob);
    };

    // Implements LeapPortalBlock.PortalPacketSender
    public static final LeapPortalBlock.PortalPacketSender PORTAL_PACKET_SENDER =
        (player, targetAddress, portalUuid, originAddress) ->
            sendPortalInitiate(player, targetAddress, portalUuid, originAddress);

    // F2: Broadcasts a chat message to all OP-level players on the server
    // confirming LAN auto-open. Also activates the client-side HUD overlay.
    // In a listen server context, the host IS the local client, so we can
    // call LanStatusHud.setActive() directly via Minecraft.execute().
    public static void broadcastLanOpenToOps(net.minecraft.server.MinecraftServer server, int port) {
        net.minecraft.network.chat.Component message = net.minecraft.network.chat.Component.literal(
            "[Leap! Pad] World opened to LAN on port " + port +
            ". Use /leappad close to stop accepting connections."
        );
        server.getPlayerList().getPlayers().forEach(player -> {
            if (player.hasPermissions(2)) {
                player.sendSystemMessage(message);
            }
        });
        // Activate HUD overlay on the local client (listen server — same JVM)
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> elder.leapp.fabric.ui.LanStatusHud.setActive(port));
        }
    }

    private static byte[] readPlayerDat(ServerPlayer player) {
        try {
            Path datFile = player.getServer()
                .getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(player.getStringUUID() + ".dat");
            if (!Files.exists(datFile)) return null;
            return Files.readAllBytes(datFile);
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] Could not read dat for {}: {}",
                player.getName().getString(), e.getMessage()
            );
            return null;
        }
    }
}
