package elder.leapp.fabric.mixin;

// EditWorldScreenMixin.java
// Injects a "Leap! Pad LAN Port" text field into the Edit World screen.
//
// World path resolution:
//   We @Shadow the levelId field (field_24188 in intermediary) which is a plain
//   String on EditWorldScreen itself. From there we use
//   Minecraft.getLevelSource().createAccess(levelId) to get the world save path.
//   This avoids any interaction with the EditWorldScreen constructor, which takes
//   an unmapped nested type (LevelSummary.WorldConfiguration / class_32$class_5143)
//   that cannot be referenced by name in Parchment-mapped code.

import elder.leapp.LeapPadCommon;
import elder.leapp.config.WorldLanConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {

    // The level ID string stored on EditWorldScreen — field_24188 in intermediary,
    // mapped to "levelId" in Parchment/Mojang 1.20.1 mappings.
    // This is the world save folder name used to open a LevelStorageAccess.
    @Shadow
    private String levelId;

    @Unique
    private EditBox leappad_lanPortField;

    @Unique
    private WorldLanConfig leappad_lanConfig;

    protected EditWorldScreenMixin(Component title) {
        super(title);
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

    // Resolves the world save directory from the shadowed levelId field.
    // Returns null if levelId is not set or path resolution fails.
    @Unique
    private Path resolveWorldSaveDir() {
        try {
            if (levelId == null || levelId.isEmpty()) return null;
            try (var access = Minecraft.getInstance()
                    .getLevelSource()
                    .createAccess(levelId)) {
                return access.getLevelPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT
                ).toAbsolutePath();
            }
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] EditWorldScreenMixin: could not resolve world save dir: {}",
                e.getMessage()
            );
            return null;
        }
    }
}
