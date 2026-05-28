package elder.leapp;

// LeapPadCommon.java
// The shared initialization point for Leap! Pad.
// Called by the Fabric entry point (LeapPadFabric) on world start.
// Only registers content that is common to all loaders — blocks, items, tags.
// Fabric-specific API calls stay in the fabric subproject.

import elder.leapp.portal.LeapPortalBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LeapPadCommon {

    // Mod ID — must match the id field in fabric.mod.json exactly
    public static final String MOD_ID = "leappad";

    // Logger — use this everywhere instead of System.out.println
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // The portal interior block instance — registered once here, referenced everywhere
    public static final Block LEAP_PORTAL_BLOCK = new LeapPortalBlock(
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_CYAN)
            .noCollission()          // Players walk through portal blocks, not into them
            .lightLevel(state -> 11) // Soft teal glow, slightly dimmer than a torch
            .noDrops()               // Portal blocks don't drop anything when broken
            .replaceable()           // Can be overwritten by other blocks without breaking
    );

    // The block item for the portal block (lets it exist in inventory / be placed by commands)
    public static final Item LEAP_PORTAL_ITEM = new BlockItem(
        LEAP_PORTAL_BLOCK,
        new Item.Properties()
    );

    // Called by LeapPadFabric on mod initialization.
    // Registers the portal block and its item with Minecraft's registry.
    public static void init() {
        LOGGER.info("Leap! Pad initializing.");

        // Register the portal block under the ID leappad:leap_portal
        Registry.register(
            BuiltInRegistries.BLOCK,
            new ResourceLocation(MOD_ID, "leap_portal"),
            LEAP_PORTAL_BLOCK
        );

        // Register the block item under the same ID
        Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(MOD_ID, "leap_portal"),
            LEAP_PORTAL_ITEM
        );

        LOGGER.info("Leap! Pad initialized.");
    }
}
