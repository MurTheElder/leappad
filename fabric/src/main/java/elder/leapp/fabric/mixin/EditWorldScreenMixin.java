package elder.leapp.fabric.mixin;

// EditWorldScreenMixin.java
// Injects a "Leap! Pad LAN Port" text field into the Edit World screen.
//
// World path resolution:
//   The EditWorldScreen constructor takes a BooleanConsumer callback and a
//   WorldConfiguration object. WorldConfiguration has no stable mapped name
//   in Parchment 1.20.1, so we type the constructor arg as Object and use
//   reflection to call getLevelSummary() on it, then getLevelId() on the result.
//   The resolved Path is stored immediately — we never hold a typed reference
//   to the unmapped WorldConfiguration class.

import elder.leapp.LeapPadCommon;
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

import java.lang.reflect.Method;
import java.nio.file.Path;

@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {

    // Resolved in the constructor injection and stored as a Path.
    // Avoids holding any typed reference to the unmapped WorldConfiguration class.
    @Unique
    private Path leappad_worldDir;

    @Unique
    private EditBox leappad_lanPortField;

    @Unique
    private WorldLanConfig leappad_lanConfig;

    protected EditWorldScreenMixin(Component title) {
        super(title);
    }

    // The real constructor signature is (BooleanConsumer, WorldConfiguration).
    // We type the second arg as Object so we don't need to name the unmapped class.
    // Reflection is used to call getLevelSummary() on the WorldConfiguration object,
    // then getLevelId() on the resulting LevelSummary, then resolve the save path.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void leappad_captureLevel(BooleanConsumer callback, Object worldConfig,
                                      CallbackInfo ci) {
        try {
            // worldConfig is LevelSummary.WorldConfiguration — call getLevelSummary()
            Method getLevelSummary = worldConfig.getClass().getMethod("getLevelSummary");
            LevelSummary summary = (LevelSummary) getLevelSummary.invoke(worldConfig);
            String levelId = summary.getLevelId();
            try (var access = Minecraft.getInstance()
                    .getLevelSource()
                    .createAccess(levelId)) {
                leappad_worldDir = access.getLevelPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT
                ).toAbsolutePath();
            }
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] EditWorldScreenMixin: could not resolve world save dir: {}",
                e.getMessage()
            );
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void leappad_addLanPortField(CallbackInfo ci) {
        if (leappad_worldDir == null) return;

        leappad_lanConfig = WorldLanConfig.load(leappad_worldDir);

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

        final Path capturedDir = leappad_worldDir;
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
}
