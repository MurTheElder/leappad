package elder.leapp.fabric.mixin;

// EditWorldScreenMixin.java
// Injects a "Leap! Pad LAN Port" text field into the Edit World screen.
//
// World path resolution (no @Shadow, no reflection):
//   We inject at the HEAD of the constructor to capture the LevelSummary
//   into a @Unique field. From there we use LevelSummary.getLevelId() with
//   Minecraft.getInstance().getLevelSource().createAccess(levelId) to get
//   the world save path — all public APIs, no private access needed.

import elder.leapp.config.WorldLanConfig;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {

    // Captured from constructor injection — avoids @Shadow and reflection entirely.
    // EditWorldScreen's actual constructor takes (BooleanConsumer, LevelSummary.WorldConfiguration).
    // We capture the WorldConfiguration and call getLevelSummary().getLevelId() from it.
    @Unique
    private LevelSummary.WorldConfiguration leappad_worldConfig;

    @Unique
    private EditBox leappad_lanPortField;

    @Unique
    private WorldLanConfig leappad_lanConfig;

    protected EditWorldScreenMixin(Component title) {
        super(title);
    }

    // Capture the WorldConfiguration from the real constructor.
    // Actual vanilla signature: EditWorldScreen(BooleanConsumer callback,
    //                                           LevelSummary.WorldConfiguration worldConfig)
    @Inject(method = "<init>", at = @At("TAIL"))
    private void leappad_captureLevel(BooleanConsumer callback,
                                      LevelSummary.WorldConfiguration worldConfig,
                                      CallbackInfo ci) {
        this.leappad_worldConfig = worldConfig;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void leappad_addLanPortField(CallbackInfo ci) {
        Path worldSaveDir = resolveWorldSaveDir();
        if (worldSaveDir == null) return;

        leappad_lanConfig = WorldLanConfig.load(worldSaveDir);

        int fieldX = 10;
        int fieldY = this.height - 48;

        leappad_lanPortField = new EditBox(
            this.font,
            fieldX, fieldY,
            110, 20,
            Component.literal("LAN port")
        );
        leappad_lanPortField.setMaxLength(6);
        leappad_lanPortField.setHint(Component.literal("e.g. 25565 (0 or -1 = disabled)"));

        if (leappad_lanConfig.lanPort != 0) {
            leappad_lanPortField.setValue(String.valueOf(leappad_lanConfig.lanPort));
        }

        final Path capturedDir = worldSaveDir;
        leappad_lanPortField.setResponder(text -> {
            if (leappad_lanConfig == null) return;
            try {
                leappad_lanConfig.lanPort = text.trim().isEmpty()
                    ? 0 : Integer.parseInt(text.trim());
                leappad_lanConfig.save(capturedDir);
            } catch (NumberFormatException ignored) {}
        });

        this.addRenderableWidget(leappad_lanPortField);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void leappad_renderLanPortLabel(GuiGraphics graphics, int mouseX, int mouseY,
                                             float delta, CallbackInfo ci) {
        if (leappad_lanPortField == null) return;
        graphics.drawString(
            this.font,
            "Leap! Pad LAN Port (auto-open)",
            leappad_lanPortField.getX(),
            leappad_lanPortField.getY() - 12,
            0xA0A0A0
        );
    }

    @Unique
    private Path resolveWorldSaveDir() {
        try {
            if (leappad_worldConfig == null) return null;
            // getLevelSummary() gives us the LevelSummary, then getLevelId() gives
            // the save folder name we need to open a LevelStorageAccess.
            String levelId = leappad_worldConfig.getLevelSummary().getLevelId();
            try (var access = Minecraft.getInstance()
                    .getLevelSource()
                    .createAccess(levelId)) {
                return access.getLevelPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT
                ).toAbsolutePath();
            }
        } catch (Exception e) {
            elder.leapp.LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] EditWorldScreenMixin: could not resolve world save dir: {}",
                e.getMessage()
            );
            return null;
        }
    }
}
