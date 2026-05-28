package elder.leapp.portal;

// LeapPortalBlock.java
// The teal portal interior block — leappad:leap_portal.
// Placed inside a valid prismarine frame when the frame is lit with fire.
// When a player walks into this block, it checks if the portal is linked
// and hands off via TransferOrchestrator.triggerPortalConnect() if so.
//
// Has a per-player re-entry guard to prevent the transfer from firing twice
// on the same walk-through (players occupy multiple blocks at once).
//
// D2-B: This class no longer calls TransferOrchestrator.onConnectionAttempt()
// directly. Instead it calls TransferOrchestrator.triggerPortalConnect(), which
// delegates to the PortalConnectTrigger bridge injected from LeapPadFabricClient.
// That bridge calls ConnectScreenMixin.setPendingPortalContext() then triggers
// a vanilla connect, which the mixin intercepts once and drives the full sequence.
// Non-portal connection routes are completely unaffected by this change.

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

    // The axis property controls which direction the portal faces:
    //   X = east-west frame (portal surface faces north/south — axis=x)
    //   Z = north-south frame (portal surface faces east/west — axis=z)
    // This matches the blockstate JSON which routes axis=x → leap_portal_ew model
    // and axis=z → leap_portal_ns model.
    public static final EnumProperty<Direction.Axis> AXIS =
        BlockStateProperties.HORIZONTAL_AXIS;

    // Per-player re-entry guard.
    // A player UUID is added here when they enter a portal block.
    // Removed after the transfer fires (or fails) so they can use portals again.
    // ConcurrentHashMap.newKeySet() gives us a thread-safe Set.
    private static final Set<UUID> activeTriggers =
        Collections.newSetFromMap(new ConcurrentHashMap<>());

    public LeapPortalBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // Default block state: axis=z (north-south facing)
        this.registerDefaultState(
            this.stateDefinition.any().setValue(AXIS, Direction.Axis.Z)
        );
    }

    // Register the AXIS property so it appears in block states
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    // Portal blocks have no collision — entities pass straight through
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    // Called every tick for each entity standing inside this block.
    // This is how we detect a player walking through the portal.
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos,
                              Entity entity) {
        // Only fire on the server side and only for players
        if (level.isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;

        UUID playerId = player.getUUID();

        // Re-entry guard — don't fire twice for the same walk-through
        if (activeTriggers.contains(playerId)) return;

        // Check if this portal is linked to a target address
        String portalUuid = PortalRegistry.getUuidForPos(pos);
        if (portalUuid == null) return; // No UUID registered for this block

        String targetAddress = PortalRegistry.getLinkedAddress(portalUuid);
        if (targetAddress == null || targetAddress.isEmpty()) {
            // Portal exists but has no linked address — do nothing
            return;
        }

        // Check cooldown — prevent re-use immediately after arriving
        if (TransferOrchestrator.isOnCooldown(playerId.toString())) {
            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Player {} tried portal {} but is on cooldown.",
                player.getName().getString(), portalUuid
            );
            return;
        }

        // Arm the re-entry guard before handing off
        activeTriggers.add(playerId);

        // Get this world's address for the mirror portal origin link
        String originAddress = PortalRegistry.getThisWorldAddress();

        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Player {} entered portal {} → {}",
            player.getName().getString(), portalUuid, targetAddress
        );

        // D2-B: Hand off via bridge instead of calling the orchestrator directly.
        // triggerPortalConnect() delegates to the PortalConnectTrigger injected at
        // startup from LeapPadFabricClient. That bridge stores portal context in
        // ConnectScreenMixin and triggers a vanilla connect on the client thread,
        // which the mixin intercepts once and drives the full transfer sequence.
        TransferOrchestrator.triggerPortalConnect(targetAddress, portalUuid, originAddress);
    }

    // Called by TransferOrchestrator when a transfer completes or fails,
    // so the player can use portals again.
    public static void clearTrigger(UUID playerId) {
        activeTriggers.remove(playerId);
    }
}
