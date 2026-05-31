package elder.leapp.fabric.mixin;

// EditWorldScreenMixin.java
// Injects a "Leap! Pad LAN Port" text field into the Edit World screen.
// When the player types a port number here, it is saved to
// [world save]/leappad/leappad_lan.json immediately.
// On next world load, LeapPadFabric reads this config and auto-opens the
// world to LAN on the specified port.
//
// World path resolution:
//   EditWorldScreen holds a LevelSummary. We shadow that field to get
//   the world's level ID, then resolve the save path via
//   Minecraft.getInstance().getLevelSource().getLevelPath(levelId).
//
// Field placement:
//   Below the existing buttons, left-aligned, 110px wide, 20px tall.
//   Hint text: "e.g. 25565 (0 or -1 = disabled)"
//
// Input validation:
//   Only digits and '-' are accepted. Non-integer input is silently ignored
//   on save — the field just won't update the config if it can't be parsed.

import elder.leapp.config.WorldLanConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {

    // Shadow the LevelSummary field on EditWorldScreen so we can get the level ID.
    // The field name under Mojang 1.20.1 mappings is "summary".
    @Shadow
    private LevelSummary summary;

    @Unique
    private EditBox leappad_lanPortField;

    @Unique
    private WorldLanConfig leappad_lanConfig;

    protected EditWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void leappad_addLanPortField(CallbackInfo ci) {
        // Resolve the world save directory from the level ID
        Path worldSaveDir = resolveWorldSaveDir();
        if (worldSaveDir == null) return;

        // Load existing config so we can pre-fill the field
        leappad_lanConfig = WorldLanConfig.load(worldSaveDir);

        // Place the field below the existing button area
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

        // Pre-fill with current value if configured
        if (leappad_lanConfig.lanPort != 0) {
            leappad_lanPortField.setValue(String.valueOf(leappad_lanConfig.lanPort));
        }

        // Save on every keystroke
        final Path capturedDir = worldSaveDir;
        leappad_lanPortField.setResponder(text -> {
            if (leappad_lanConfig == null) return;
            try {
                leappad_lanConfig.lanPort = text.trim().isEmpty() ? 0 : Integer.parseInt(text.trim());
                leappad_lanConfig.save(capturedDir);
            } catch (NumberFormatException ignored) {
                // Non-integer input — don't update config
            }
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
            if (summary == null) return null;
            String levelId = summary.getLevelId();
            return Minecraft.getInstance()
                .getLevelSource()
                .getLevelPath(levelId);
        } catch (Exception e) {
            elder.leapp.LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] EditWorldScreenMixin: could not resolve world save dir: {}", e.getMessage()
            );
            return null;
        }
    }
}
