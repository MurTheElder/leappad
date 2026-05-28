package elder.leapp.portal;

// PortalIgnitionHandler.java
// Listens for fire placement events and checks whether a valid portal frame exists.
// Runs a cheap pre-validation filter first to rule out impossible positions,
// then runs the full vanilla portal frame trace for each qualifying orientation.
//
// Pre-validation filter (runs on any fire placed on a solid block):
//   1. Block below fire must be a frameBlocks config block
//   2. Check E/W neighbours — if both are frame blocks, queue E-W validation
//   3. Check N/S neighbours — if both are frame blocks, queue N-S validation
//   4. If neither qualifies, stop
//
// If one orientation passes: register one UUID covering that frame.
// If both pass (fire at a shared corner): register one UUID covering both frames.
// If neither passes: do nothing — fire burns normally.
//
// Called from FirePlaceMixin (Fabric) when new fire is placed.

import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class PortalIgnitionHandler {

    // Minimum and maximum portal interior sizes (matching vanilla nether portal rules)
    private static final int MIN_WIDTH  = 2;
    private static final int MAX_WIDTH  = 21;
    private static final int MIN_HEIGHT = 3;
    private static final int MAX_HEIGHT = 21;

    // Called from FirePlaceMixin when fire is placed at firePos.
    // level must be server-side.
    public static void onFirePlaced(Level level, BlockPos firePos) {
        if (level.isClientSide()) return;

        // The block below the fire must be a frame block
        BlockPos below = firePos.below();
        if (!isFrameBlock(level, below)) return;

        boolean queueEW = false;
        boolean queueNS = false;

        // Check E/W pair — fire could be at the base of an E-W oriented frame
        BlockPos east = below.east();
        BlockPos west = below.west();
        if (isFrameBlock(level, east) && isFrameBlock(level, west)) {
            queueEW = true;
        }

        // Check N/S pair — fire could be at the base of a N-S oriented frame
        BlockPos north = below.north();
        BlockPos south = below.south();
        if (isFrameBlock(level, north) && isFrameBlock(level, south)) {
            queueNS = true;
        }

        if (!queueEW && !queueNS) return; // Neither orientation is possible here

        // Run frame trace for each queued orientation
        FrameResult ewResult = queueEW ? traceFrame(level, firePos, Direction.Axis.X) : null;
        FrameResult nsResult = queueNS ? traceFrame(level, firePos, Direction.Axis.Z) : null;

        boolean ewValid = ewResult != null && ewResult.valid;
        boolean nsValid = nsResult != null && nsResult.valid;

        if (!ewValid && !nsValid) return; // Neither trace passed — fire burns normally

        // Collect all corners from passing frames
        List<BlockPos> allCorners = new ArrayList<>();
        if (ewValid)  allCorners.addAll(ewResult.corners);
        if (nsValid)  allCorners.addAll(nsResult.corners);

        // Register one UUID covering all passing frames
        String uuid = PortalRegistry.registerPortal(allCorners);

        // Fill each passing frame's interior with LeapPortalBlock
        if (ewValid) fillInterior(level, ewResult, Direction.Axis.X);
        if (nsValid) fillInterior(level, nsResult, Direction.Axis.Z);

        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Portal ignited at {} — UUID: {} (EW={}, NS={})",
            firePos, uuid, ewValid, nsValid
        );
    }

    // -------------------------------------------------------
    // Frame trace — adapted from vanilla nether portal logic
    // -------------------------------------------------------

    // Holds the result of a frame trace attempt
    private static class FrameResult {
        boolean valid = false;
        List<BlockPos> corners = new ArrayList<>();    // All 4 corner positions
        List<BlockPos> interior = new ArrayList<>();   // All interior positions to fill
        BlockPos bottomLeft;                           // Starting corner for fill iteration
        int width;
        int height;
    }

    // Traces a portal frame in the given axis orientation starting from firePos.
    // Returns a FrameResult with valid=true if a complete frame was found.
    private static FrameResult traceFrame(Level level, BlockPos firePos, Direction.Axis axis) {
        FrameResult result = new FrameResult();

        // Determine which directions are "left/right" and "up" for this axis
        Direction left  = axis == Direction.Axis.X ? Direction.WEST  : Direction.NORTH;
        Direction right = axis == Direction.Axis.X ? Direction.EAST   : Direction.SOUTH;
        Direction up    = Direction.UP;
        Direction down  = Direction.DOWN;

        // Find the bottom-left corner by walking left and down from fire position
        BlockPos cursor = firePos;
        int stepsLeft = 0;
        while (stepsLeft <= MAX_WIDTH && !isFrameBlock(level, cursor.relative(left))) {
            cursor = cursor.relative(left);
            stepsLeft++;
        }
        if (!isFrameBlock(level, cursor.relative(left))) return result; // No left wall

        while (stepsLeft <= MAX_WIDTH && !isFrameBlock(level, cursor.relative(down))) {
            cursor = cursor.relative(down);
        }
        if (!isFrameBlock(level, cursor.relative(down))) return result; // No floor

        result.bottomLeft = cursor;

        // Measure width (interior columns)
        int width = 0;
        BlockPos widthCursor = cursor;
        while (width < MAX_WIDTH && !isFrameBlock(level, widthCursor.relative(right))) {
            widthCursor = widthCursor.relative(right);
            width++;
        }
        if (width < MIN_WIDTH || !isFrameBlock(level, widthCursor.relative(right))) return result;
        result.width = width;

        // Measure height (interior rows)
        int height = 0;
        BlockPos heightCursor = cursor;
        while (height < MAX_HEIGHT && !isFrameBlock(level, heightCursor.relative(up))) {
            heightCursor = heightCursor.relative(up);
            height++;
        }
        if (height < MIN_HEIGHT || !isFrameBlock(level, heightCursor.relative(up))) return result;
        result.height = height;

        // Verify top frame and right wall
        BlockPos topLeft  = cursor.relative(up, height + 1);
        BlockPos topRight = topLeft.relative(right, width + 1);
        BlockPos botRight = cursor.relative(right, width + 1);

        if (!isFrameBlock(level, topLeft)  || !isFrameBlock(level, topRight) ||
            !isFrameBlock(level, botRight)) return result;

        // Collect corners
        result.corners.add(cursor);                                // bottom-left
        result.corners.add(botRight);                              // bottom-right
        result.corners.add(topLeft);                               // top-left
        result.corners.add(topRight);                              // top-right

        // Verify interior is clear and collect positions for filling
        for (int h = 0; h < height; h++) {
            for (int w = 0; w < width; w++) {
                BlockPos interior = cursor.relative(right, w + 1).relative(up, h + 1);
                BlockState state = level.getBlockState(interior);
                // Interior must be air or a replaceable block — not solid
                if (!state.isAir() && !state.canBeReplaced()) return result;
                result.interior.add(interior);
            }
        }

        result.valid = true;
        return result;
    }

    // Fills all interior positions with LeapPortalBlock facing the given axis
    private static void fillInterior(Level level, FrameResult frame, Direction.Axis axis) {
        BlockState portalState = LeapPadCommon.LEAP_PORTAL_BLOCK.defaultBlockState()
            .setValue(LeapPortalBlock.AXIS, axis);
        for (BlockPos pos : frame.interior) {
            level.setBlock(pos, portalState, 18); // Flag 18 = update + notify, no re-render cycle
        }
    }

    // -------------------------------------------------------
    // Frame block check
    // -------------------------------------------------------

    // Returns true if the block at pos is in the leappad:frame_blocks config list
    private static boolean isFrameBlock(Level level, BlockPos pos) {
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(level.getBlockState(pos).getBlock())
            .toString();
        for (String frameBlock : LeapPadConfig.frameBlocks) {
            if (frameBlock.equals(blockId)) return true;
        }
        return false;
    }
}
