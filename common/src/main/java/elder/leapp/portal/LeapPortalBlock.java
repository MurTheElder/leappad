package elder.leapp.portal;

// LeapPortalBlock.java
// The teal portal interior block — leappad:leap_portal.
// Placed inside a valid prismarine frame when the frame is lit with fire.
// When a player walks into this block, it checks if the portal is linked
// and sends a PORTAL_INITIATE packet to the client if so.
//
// Has a per-player re-entry guard to prevent the transfer from firing twice
// on the same walk-through (players occupy multiple blocks at once).
//
// Architecture change: LeapPortalBlock no longer calls TransferOrchestrator
// or any connect bridge directly. Instead it sends a leappad:portal_initiate
// packet to the player's client. The client receives this packet and calls
// TransferOrchestrator.onConnectionAttempt() — the same entry point used
// by direct connect and server list paths. All paths converge on the same
// client-side sequence.

import elder.leapp.LeapPadCommon;
import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LeapPortalBlock extends Block {

    public static final EnumProperty<Direction.Axis> AXIS =
        BlockStateProperties.HORIZONTAL_AXIS;

    // Per-player re-entry guard — prevents the transfer from firing twice
    // for the same walk-through. Cleared when the transfer completes or fails.
    private static final Set<UUID> activeTriggers =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Bridge interface — implemented in FabricNetworking and injected at startup.
    // Keeps common code free of Fabric-specific imports.
    public interface PortalPacketSender {
        void sendPortalInitiate(ServerPlayer player, String targetAddress,
                                String portalUuid, String originAddress);
    }

    private static PortalPacketSender portalPacketSender;

    public static void setPortalPacketSender(PortalPacketSender sender) {
        portalPacketSender = sender;
    }

    public LeapPortalBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(
            this.stateDefinition.any().setValue(AXIS, Direction.Axis.Z)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos,
                              Entity entity) {
        // Only fire on the server side and only for players
        if (level.isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;

        UUID playerId = player.getUUID();

        // Re-entry guard
        if (activeTriggers.contains(playerId)) return;

        // Check if this portal is linked — use convenience method to avoid
        // separate UUID lookup followed by address lookup.
        String targetAddress = PortalRegistry.getLinkedAddressForPos(pos);
        if (targetAddress == null || targetAddress.isEmpty()) return;

        // Also get the portal UUID for the initiate packet.
        String portalUuid = PortalRegistry.getUuidForPos(pos);
        if (portalUuid == null) return;

        // Check cooldown
        if (TransferOrchestrator.isOnCooldown(playerId.toString())) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Player {} tried portal {} but is on cooldown.",
                player.getName().getString(), portalUuid
            );
            return;
        }

        // Arm the re-entry guard
        activeTriggers.add(playerId);

        String originAddress = PortalRegistry.getThisWorldAddress();

        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Player {} entered portal {} → {}",
            player.getName().getString(), portalUuid, targetAddress
        );

        // Send portal_initiate packet to the client.
        // The client receives this, calls TransferOrchestrator.onConnectionAttempt(),
        // and runs the full pre-connection sequence before any vanilla connect fires.
        if (portalPacketSender != null) {
            portalPacketSender.sendPortalInitiate(player, targetAddress, portalUuid, originAddress);
        } else {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] PortalPacketSender not injected — portal trigger dropped for {}.",
                player.getName().getString()
            );
            activeTriggers.remove(playerId);
        }
    }

    // Called by TransferOrchestrator when a transfer completes or fails,
    // so the player can use portals again.
    public static void clearTrigger(UUID playerId) {
        activeTriggers.remove(playerId);
    }
}
