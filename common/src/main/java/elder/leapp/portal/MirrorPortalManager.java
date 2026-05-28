package elder.leapp.portal;

// MirrorPortalManager.java
// Called during host pre-arrival work (transfer step 7, portal path only).
// Runs the placement algorithm from Architecture Plan Part 1, Decision 2,
// builds the portal frame, fills the interior with LeapPortalBlock,
// links it back to the origin address, and registers the UUID.
//
// Placement algorithm:
//   1. Take one corner of the origin portal and use its XZ as the starting column
//   2. Run column check on that column
//   3. Pass → build there. Fail → check 8 adjacent columns in N/E/S/W/NE/SE/SW/NW order
//   4. Still fail → expand search radius by 1, sweep new ring, repeat
//   5. Exhaust maxPlacementSearchRadius → spawn player at world default spawn, no portal built
//
// Portal orientation is always north-south (axis=Z).
// Frame uses the first block in the host world's frameBlocks config.

import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MirrorPortalManager {

    // Column check constants
    private static final int MIN_AIR_HEIGHT     = 4;   // At least 4 consecutive air blocks
    private static final int VERTICAL_CLEARANCE = 64;  // 64 non-obstructive blocks above

    // Result of a placement attempt
    public enum PlacementResult {
        SUCCESS,
        FAILED_NO_SPACE   // Search exhausted — player will spawn at world default spawn
    }

    public static class PlacementOutcome {
        public final PlacementResult result;
        public final BlockPos spawnPos;     // Where the player should spawn
        public final String portalUuid;     // UUID of the placed portal (null on failure)

        public PlacementOutcome(PlacementResult result, BlockPos spawnPos, String portalUuid) {
            this.result = result;
            this.spawnPos = spawnPos;
            this.portalUuid = portalUuid;
        }
    }

    // Main entry point. Called by TransferOrchestrator step 7.
    // originCornerPos — one corner of the portal the player came through (XZ used as starting column)
    // originAddress — the address of the world the player is coming from
    // level — the server level to place the portal in
    public static PlacementOutcome placePortal(BlockPos originCornerPos,
                                                String originAddress,
                                                ServerLevel level) {
        int startX = originCornerPos.getX();
        int startZ = originCornerPos.getZ();

        // Try the exact starting column first
        Integer foundY = checkColumn(level, startX, startZ);
        if (foundY != null) {
            return buildPortal(level, new BlockPos(startX, foundY, startZ), originAddress);
        }

        // Spiral outward
        int maxRadius = LeapPadConfig.maxPlacementSearchRadius;
        for (int radius = 1; radius <= maxRadius; radius++) {
            List<int[]> ring = getRingColumns(startX, startZ, radius);
            for (int[] col : ring) {
                foundY = checkColumn(level, col[0], col[1]);
                if (foundY != null) {
                    return buildPortal(level, new BlockPos(col[0], foundY, col[1]), originAddress);
                }
            }
        }

        // Search exhausted — player spawns at world default spawn
        LeapPadCommon.LOGGER.warn(
            "[Leap! Pad] Mirror portal placement failed after searching radius {}. " +
            "Player will spawn at world default spawn.", maxRadius
        );
        BlockPos defaultSpawn = level.getSharedSpawnPos();
        return new PlacementOutcome(PlacementResult.FAILED_NO_SPACE, defaultSpawn, null);
    }

    // -------------------------------------------------------
    // Column check
    // -------------------------------------------------------

    // Checks whether a valid portal placement exists in the column at (x, z).
    // Returns the Y coordinate of the lowest valid placement, or null if none found.
    private static Integer checkColumn(ServerLevel level, int x, int z) {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - MIN_AIR_HEIGHT - 1;

        for (int y = minY; y <= maxY; y++) {
            BlockPos floor = new BlockPos(x, y - 1, z);
            BlockPos air   = new BlockPos(x, y,     z);

            // Floor must be solid
            if (!level.getBlockState(floor).isSolid()) continue;

            // Need MIN_AIR_HEIGHT consecutive non-obstructive blocks above floor
            boolean clearBelow = true;
            for (int h = 0; h < MIN_AIR_HEIGHT; h++) {
                if (!isNonObstructive(level, new BlockPos(x, y + h, z))) {
                    clearBelow = false;
                    break;
                }
            }
            if (!clearBelow) continue;

            // Need VERTICAL_CLEARANCE non-obstructive blocks above
            int clearCount = 0;
            for (int h = 0; h < VERTICAL_CLEARANCE; h++) {
                if (isNonObstructive(level, new BlockPos(x, y + h, z))) {
                    clearCount++;
                } else {
                    break;
                }
            }
            if (clearCount < VERTICAL_CLEARANCE) continue;

            return y;
        }
        return null;
    }

    // -------------------------------------------------------
    // Portal construction
    // -------------------------------------------------------

    // Builds a 2-wide, 3-tall north-south portal frame at the given base position,
    // fills the interior, links it to originAddress, and registers the UUID.
    private static PlacementOutcome buildPortal(ServerLevel level, BlockPos base,
                                                 String originAddress) {
        Block frameBlock = getFrameBlock();
        BlockState frameState = frameBlock.defaultBlockState();
        BlockState portalState = LeapPadCommon.LEAP_PORTAL_BLOCK.defaultBlockState()
            .setValue(LeapPortalBlock.AXIS, Direction.Axis.Z);

        // Frame layout (north-south orientation, 2 wide x 3 tall interior):
        // F F F F    (top frame row, 4 blocks including corners)
        // F P P F    (interior rows x3)
        // F P P F
        // F P P F
        // F F F F    (bottom frame row)
        // P = portal interior, F = frame block

        int x = base.getX();
        int y = base.getY();
        int z = base.getZ();

        // Bottom row
        setFrame(level, x,     y - 1, z,     frameState);
        setFrame(level, x + 1, y - 1, z,     frameState);
        setFrame(level, x + 2, y - 1, z,     frameState);
        setFrame(level, x + 3, y - 1, z,     frameState);

        // Side columns (3 interior rows)
        for (int row = 0; row < 3; row++) {
            setFrame(level, x,     y + row, z, frameState);
            setFrame(level, x + 3, y + row, z, frameState);
        }

        // Top row
        setFrame(level, x,     y + 3, z, frameState);
        setFrame(level, x + 1, y + 3, z, frameState);
        setFrame(level, x + 2, y + 3, z, frameState);
        setFrame(level, x + 3, y + 3, z, frameState);

        // Interior (2 wide x 3 tall)
        List<BlockPos> interior = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            interior.add(new BlockPos(x + 1, y + row, z));
            interior.add(new BlockPos(x + 2, y + row, z));
        }
        for (BlockPos pos : interior) {
            level.setBlock(pos, portalState, 18);
        }

        // Corners for registry
        List<BlockPos> corners = List.of(
            new BlockPos(x,     y - 1, z),
            new BlockPos(x + 3, y - 1, z),
            new BlockPos(x,     y + 3, z),
            new BlockPos(x + 3, y + 3, z)
        );

        // Register and link
        String uuid = PortalRegistry.registerPortal(corners);
        PortalRegistry.linkPortal(uuid, originAddress);

        // Player spawns inside the portal interior (centre column, bottom row)
        BlockPos spawnPos = new BlockPos(x + 1, y, z);

        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] Mirror portal placed at {} — UUID: {}, linked to {}",
            base, uuid, originAddress
        );

        return new PlacementOutcome(PlacementResult.SUCCESS, spawnPos, uuid);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private static void setFrame(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, 18);
    }

    // Returns true if the block at pos is in the nonObstructiveBlocks config list
    private static boolean isNonObstructive(ServerLevel level, BlockPos pos) {
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
            .getKey(level.getBlockState(pos).getBlock())
            .toString();
        for (String allowed : LeapPadConfig.nonObstructiveBlocks) {
            if (allowed.equals(blockId)) return true;
        }
        return false;
    }

    // Returns the Block instance for the first entry in frameBlocks config
    private static Block getFrameBlock() {
        if (LeapPadConfig.frameBlocks.length > 0) {
            ResourceLocation id = new ResourceLocation(LeapPadConfig.frameBlocks[0]);
            Block block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (block != Blocks.AIR) return block;
        }
        return Blocks.PRISMARINE; // Fallback — should never happen given config validation
    }

    // Returns the ring of columns at the given radius around (startX, startZ).
    // Order: N, E, S, W, then NE, SE, SW, NW edges, spiralling outward.
    private static List<int[]> getRingColumns(int startX, int startZ, int radius) {
        List<int[]> ring = new ArrayList<>();
        // Cardinal points first
        ring.add(new int[]{startX,          startZ - radius}); // N
        ring.add(new int[]{startX + radius, startZ         }); // E
        ring.add(new int[]{startX,          startZ + radius}); // S
        ring.add(new int[]{startX - radius, startZ         }); // W
        // Diagonal points
        ring.add(new int[]{startX + radius, startZ - radius}); // NE
        ring.add(new int[]{startX + radius, startZ + radius}); // SE
        ring.add(new int[]{startX - radius, startZ + radius}); // SW
        ring.add(new int[]{startX - radius, startZ - radius}); // NW
        // Fill in the edges between cardinal and diagonal points
        for (int i = 1; i < radius; i++) {
            ring.add(new int[]{startX + i,          startZ - radius}); // N edge going E
            ring.add(new int[]{startX + radius,     startZ - i     }); // E edge going S
            ring.add(new int[]{startX + i,          startZ + radius}); // S edge going E
            ring.add(new int[]{startX - radius,     startZ + i     }); // W edge going S
            ring.add(new int[]{startX - i,          startZ - radius}); // N edge going W
            ring.add(new int[]{startX + radius,     startZ + i     }); // E edge going N
            ring.add(new int[]{startX - i,          startZ + radius}); // S edge going W
            ring.add(new int[]{startX - radius,     startZ - i     }); // W edge going N
        }
        return ring;
    }
}
