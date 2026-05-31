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

import java.io.IOException;
import java.nio.file.Path;

@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {

    // Captured from constructor injection — avoids @Shadow and reflection entirely.
    @Unique
    private LevelSummary leappad_levelSummary;

    @Unique
    private EditBox leappad_lanPortField;

    @Unique
    private WorldLanConfig leappad_lanConfig;

    protected EditWorldScreenMixin(Component title) {
        super(title);
    }

    // Capture the LevelSummary as early as possible.
    // EditWorldScreen constructor signature: (Screen lastScreen, LevelSummary summary)
    @Inject(method = "<init>", at = @At("TAIL"))
    private void leappad_captureLevel(Screen lastScreen, LevelSummary summary,
                                       CallbackInfo ci) {
        this.leappad_levelSummary = summary;
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
            if (leappad_levelSummary == null) return null;
            String levelId = leappad_levelSummary.getLevelId();
            // createAccess returns a LevelStorageAccess — getLevelPath(ROOT) gives
            // the world save directory. We close the access immediately after.
            try (var access = Minecraft.getInstance()
                    .getLevelSource()
                    .createAccess(levelId)) {
                return access.getLevelPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT
                ).toAbsolutePath();
            }
        } catch (IOException | Exception e) {
            elder.leapp.LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] EditWorldScreenMixin: could not resolve world save dir: {}",
                e.getMessage()
            );
            return null;
        }
    }
}
