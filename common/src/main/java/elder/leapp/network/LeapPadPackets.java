package elder.leapp.network;

// LeapPadPackets.java
// Single source of truth for every packet the mod sends.
// Defines all channel ID strings as constants and all packet types as
// static nested classes. Each nested class holds its data fields and
// static encode/decode methods.
//
// No logic lives here — these are pure data containers.
// Having everything in one file ensures channel IDs never drift out of sync
// between the send and receive sides.
//
// Added: PORTAL_INITIATE — S→C packet sent by LeapPortalBlock when a player
// walks into a linked portal. Carries target address, portal UUID, and origin
// address to the client so it can start the transfer sequence. This replaces
// the PortalConnectTrigger bridge which required a client-side vanilla connect
// trigger — the client now starts the sequence directly on receipt of this packet.

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public class LeapPadPackets {

    // -------------------------------------------------------
    // Channel IDs
    // All packet channels are declared here as ResourceLocations.
    // Reference these constants everywhere — never hardcode strings.
    // -------------------------------------------------------

    // Step 3 — Client sends probe to host: client IP + Leap! Forward flag
    public static final ResourceLocation PROBE =
        new ResourceLocation("leappad", "probe");

    // Step 3 — Host sends probe response back: reachable, has LeapPad, transfer key
    public static final ResourceLocation PROBE_RESPONSE =
        new ResourceLocation("leappad", "probe_response");

    // Step 4 — Host sends warning screen to client when target has no Leap! Pad
    public static final ResourceLocation WARNING_SCREEN =
        new ResourceLocation("leappad", "warning_screen");

    // Step 4 — Client cancels from warning screen
    public static final ResourceLocation TRANSFER_CANCEL =
        new ResourceLocation("leappad", "transfer_cancel");

    // Step 6 — Client sends profile dat blob to host before vanilla join
    public static final ResourceLocation PROFILE_DAT_SEND =
        new ResourceLocation("leappad", "profile_dat_send");

    // Step 8 — Host sends its full portal UUID list to client for deconfliction
    public static final ResourceLocation UUID_LIST =
        new ResourceLocation("leappad", "uuid_list");

    // Step 10 — Client sends the agreed portal UUID back to host
    public static final ResourceLocation UUID_CONFIRM =
        new ResourceLocation("leappad", "uuid_confirm");

    // Step 10 — Host sends Leap! Forward loader cache + mods folder (chunked, if flag present)
    public static final ResourceLocation LEAPFORWARD_CACHE =
        new ResourceLocation("leappad", "leapforward_cache");

    // Step 11 — Client signals all pre-connection work is complete
    public static final ResourceLocation READY =
        new ResourceLocation("leappad", "ready");

    // Step 12 — Host echoes the ready signal back to client
    public static final ResourceLocation READY_ECHO =
        new ResourceLocation("leappad", "ready_echo");

    // Steps 14+15 — Host pushes current player dat to client on autosave or disconnect
    public static final ResourceLocation PROFILE_DAT_PUSH =
        new ResourceLocation("leappad", "profile_dat_push");

    // Portal path entry point — S→C
    // Sent by LeapPortalBlock when a player walks into a linked portal.
    // The client receives this and calls TransferOrchestrator.onConnectionAttempt()
    // directly, starting the sequence without needing a vanilla connect trigger.
    public static final ResourceLocation PORTAL_INITIATE =
        new ResourceLocation("leappad", "portal_initiate");

    // -------------------------------------------------------
    // Packet data classes
    // Each class holds the fields for one packet direction.
    // encode() writes fields to the buffer in order.
    // decode() reads them back in the same order.
    // -------------------------------------------------------

    // leappad:probe — C→S, Step 3
    public static class ProbePacket {
        public final String clientIp;
        public final boolean leapForward;

        public ProbePacket(String clientIp, boolean leapForward) {
            this.clientIp = clientIp;
            this.leapForward = leapForward;
        }

        public static void encode(ProbePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.clientIp);
            buf.writeBoolean(pkt.leapForward);
        }

        public static ProbePacket decode(FriendlyByteBuf buf) {
            return new ProbePacket(buf.readUtf(), buf.readBoolean());
        }
    }

    // leappad:probe_response — S→C, Step 3
    public static class ProbeResponsePacket {
        public final boolean reachable;
        public final boolean hasLeapPad;
        public final String transferKey;

        public ProbeResponsePacket(boolean reachable, boolean hasLeapPad, String transferKey) {
            this.reachable = reachable;
            this.hasLeapPad = hasLeapPad;
            this.transferKey = transferKey;
        }

        public static void encode(ProbeResponsePacket pkt, FriendlyByteBuf buf) {
            buf.writeBoolean(pkt.reachable);
            buf.writeBoolean(pkt.hasLeapPad);
            buf.writeUtf(pkt.transferKey);
        }

        public static ProbeResponsePacket decode(FriendlyByteBuf buf) {
            return new ProbeResponsePacket(buf.readBoolean(), buf.readBoolean(), buf.readUtf());
        }
    }

    // leappad:warning_screen — S→C, Step 4
    public static class WarningScreenPacket {
        public final String targetAddress;

        public WarningScreenPacket(String targetAddress) {
            this.targetAddress = targetAddress;
        }

        public static void encode(WarningScreenPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.targetAddress);
        }

        public static WarningScreenPacket decode(FriendlyByteBuf buf) {
            return new WarningScreenPacket(buf.readUtf());
        }
    }

    // leappad:transfer_cancel — C→S, Step 4
    public static class TransferCancelPacket {
        public final String transferKey;

        public TransferCancelPacket(String transferKey) {
            this.transferKey = transferKey;
        }

        public static void encode(TransferCancelPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.transferKey);
        }

        public static TransferCancelPacket decode(FriendlyByteBuf buf) {
            return new TransferCancelPacket(buf.readUtf());
        }
    }

    // leappad:profile_dat_send — C→S, Step 6
    public static class ProfileDatSendPacket {
        public final String profileUuid;
        public final byte[] datBlob;
        public final boolean leapForward;

        public ProfileDatSendPacket(String profileUuid, byte[] datBlob, boolean leapForward) {
            this.profileUuid = profileUuid;
            this.datBlob = datBlob;
            this.leapForward = leapForward;
        }

        public static void encode(ProfileDatSendPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.profileUuid);
            buf.writeByteArray(pkt.datBlob);
            buf.writeBoolean(pkt.leapForward);
        }

        public static ProfileDatSendPacket decode(FriendlyByteBuf buf) {
            return new ProfileDatSendPacket(buf.readUtf(), buf.readByteArray(), buf.readBoolean());
        }
    }

    // leappad:uuid_list — S→C, Step 8
    public static class UuidListPacket {
        public final String[] uuids;

        public UuidListPacket(String[] uuids) {
            this.uuids = uuids;
        }

        public static void encode(UuidListPacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.uuids.length);
            for (String uuid : pkt.uuids) buf.writeUtf(uuid);
        }

        public static UuidListPacket decode(FriendlyByteBuf buf) {
            int count = buf.readInt();
            String[] uuids = new String[count];
            for (int i = 0; i < count; i++) uuids[i] = buf.readUtf();
            return new UuidListPacket(uuids);
        }
    }

    // leappad:uuid_confirm — C→S, Step 10
    public static class UuidConfirmPacket {
        public final String agreedUuid;

        public UuidConfirmPacket(String agreedUuid) {
            this.agreedUuid = agreedUuid;
        }

        public static void encode(UuidConfirmPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.agreedUuid);
        }

        public static UuidConfirmPacket decode(FriendlyByteBuf buf) {
            return new UuidConfirmPacket(buf.readUtf());
        }
    }

    // leappad:leapforward_cache — S→C, Step 10
    public static class LeapForwardCachePacket {
        public final int chunkIndex;
        public final int totalChunks;
        public final byte[] data;

        public LeapForwardCachePacket(int chunkIndex, int totalChunks, byte[] data) {
            this.chunkIndex = chunkIndex;
            this.totalChunks = totalChunks;
            this.data = data;
        }

        public static void encode(LeapForwardCachePacket pkt, FriendlyByteBuf buf) {
            buf.writeInt(pkt.chunkIndex);
            buf.writeInt(pkt.totalChunks);
            buf.writeByteArray(pkt.data);
        }

        public static LeapForwardCachePacket decode(FriendlyByteBuf buf) {
            return new LeapForwardCachePacket(buf.readInt(), buf.readInt(), buf.readByteArray());
        }
    }

    // leappad:ready — C→S, Step 11
    public static class ReadyPacket {
        public final String transferKey;

        public ReadyPacket(String transferKey) {
            this.transferKey = transferKey;
        }

        public static void encode(ReadyPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.transferKey);
        }

        public static ReadyPacket decode(FriendlyByteBuf buf) {
            return new ReadyPacket(buf.readUtf());
        }
    }

    // leappad:ready_echo — S→C, Step 12
    public static class ReadyEchoPacket {
        public final String transferKey;

        public ReadyEchoPacket(String transferKey) {
            this.transferKey = transferKey;
        }

        public static void encode(ReadyEchoPacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.transferKey);
        }

        public static ReadyEchoPacket decode(FriendlyByteBuf buf) {
            return new ReadyEchoPacket(buf.readUtf());
        }
    }

    // leappad:profile_dat_push — S→C, Steps 14+15
    public static class ProfileDatPushPacket {
        public final byte[] datBlob;

        public ProfileDatPushPacket(byte[] datBlob) {
            this.datBlob = datBlob;
        }

        public static void encode(ProfileDatPushPacket pkt, FriendlyByteBuf buf) {
            buf.writeByteArray(pkt.datBlob);
        }

        public static ProfileDatPushPacket decode(FriendlyByteBuf buf) {
            return new ProfileDatPushPacket(buf.readByteArray());
        }
    }

    // leappad:portal_initiate — S→C, Portal path entry point
    // Sent by LeapPortalBlock on the server thread when a player walks into a portal.
    // The client receives this packet and calls TransferOrchestrator.onConnectionAttempt()
    // directly — no vanilla connect trigger needed at this stage.
    public static class PortalInitiatePacket {
        public final String targetAddress;   // The world address this portal is linked to
        public final String portalUuid;      // UUID of the portal the player walked through
        public final String originAddress;   // This world's address (for mirror portal linking)

        public PortalInitiatePacket(String targetAddress, String portalUuid, String originAddress) {
            this.targetAddress = targetAddress;
            this.portalUuid = portalUuid;
            this.originAddress = originAddress;
        }

        public static void encode(PortalInitiatePacket pkt, FriendlyByteBuf buf) {
            buf.writeUtf(pkt.targetAddress);
            buf.writeUtf(pkt.portalUuid);
            buf.writeUtf(pkt.originAddress);
        }

        public static PortalInitiatePacket decode(FriendlyByteBuf buf) {
            return new PortalInitiatePacket(buf.readUtf(), buf.readUtf(), buf.readUtf());
        }
    }
}
