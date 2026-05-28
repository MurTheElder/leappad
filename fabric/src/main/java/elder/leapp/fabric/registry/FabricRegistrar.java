package elder.leapp.fabric.registry;

// FabricRegistrar.java
// Registers LeapPortalBlock and its associated block item with Fabric's registry system.
// Also registers the leappad:frame_blocks block tag.
//
// Block and item registration is already handled in LeapPadCommon.init() using
// Minecraft's vanilla Registry.register() directly — which works on both loaders.
// This class handles any Fabric-specific registration that can't be done in common code,
// such as adding blocks to Fabric's item groups or registering render layers.

import elder.leapp.LeapPadCommon;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.RenderType;

public class FabricRegistrar {

    // Called from LeapPadFabricClient after common init.
    // Sets the render layer for the portal block so it renders as translucent
    // (same render pipeline as water and stained glass — allows see-through effect).
    public static void registerClientSide() {
        BlockRenderLayerMap.INSTANCE.putBlock(
            LeapPadCommon.LEAP_PORTAL_BLOCK,
            RenderType.translucent()
        );
    }
}
