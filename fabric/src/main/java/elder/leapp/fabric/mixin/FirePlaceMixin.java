package elder.leapp.fabric.mixin;

// FirePlaceMixin.java
// Intercepts fire block placement on the server side and calls PortalIgnitionHandler.
// PortalIgnitionHandler runs the pre-validation filter and frame trace to determine
// whether the fire has been placed inside a valid portal frame.
//
// Targets BaseFireBlock.onPlace() — the method called whenever fire is placed in the world,
// covering both vanilla fire and soul fire. We only care about the placement event;
// the handler checks the surrounding blocks itself.

import elder.leapp.portal.PortalIgnitionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BaseFireBlock.class)
public class FirePlaceMixin {

    // Injects at the end of onPlace() so we run after vanilla has finished
    // placing the fire block (including any neighbour updates).
    @Inject(method = "onPlace", at = @At("TAIL"))
    private void leappad_onFirePlaced(BlockState state, Level level, BlockPos pos,
                                       BlockState oldState, boolean isMoving,
                                       CallbackInfo ci) {
        // Server side only — portal ignition is a world-side event
        if (level.isClientSide()) return;

        PortalIgnitionHandler.onFirePlaced(level, pos);
    }
}
