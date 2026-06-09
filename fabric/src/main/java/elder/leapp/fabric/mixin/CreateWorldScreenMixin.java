package elder.leapp.fabric.mixin;

// CreateWorldScreenMixin.java
// Injects a "Leap! Pad LAN Port" text field into the Create World screen.
//
// The world save directory does not exist yet when this screen is open, so we
// cannot write leappad_lan.json directly. Instead, the typed port value is staged
// in WorldLanConfig.stagedLanPort. LeapPadFabric.SERVER_STARTING reads and
// consumes the staged value once the world save path becomes available, writing
// it to the correct location before the world opens to anyone.
//
// WorldCreationUiState.getTargetFolder() returns the intended save folder name
// as the player types their world name. This is used only for display/logging;
// the actual path resolution happens in SERVER_STARTING.

import elder.leapp.config.WorldLanConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin extends Screen {

    @Unique
    private EditBox leappad_lanPortField;

    protected CreateWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void leappad_addLanPortField(CallbackInfo ci) {
        // Position matches the Edit World screen for visual consistency:
        // bottom-left, 110px wide, 20px tall
        leappad_lanPortField = new EditBox(
            this.font,
            10, this.height - 48,
            110, 20,
            Component.literal("LAN port")
        );
        leappad_lanPortField.setMaxLength(6);
        leappad_lanPortField.setHint(Component.literal("e.g. 25565 (0 or -1 = off)"));

        // Pre-fill from any existing staged value (e.g. player navigated back and
        // re-opened the screen)
        int staged = WorldLanConfig.getStagedLanPort();
        if (staged > 0) {
            leappad_lanPortField.setValue(String.valueOf(staged));
        }

        leappad_lanPortField.setResponder(text -> {
            try {
                int port = text.trim().isEmpty() ? -1 : Integer.parseInt(text.trim());
                WorldLanConfig.setStagedLanPort(port);
            } catch (NumberFormatException ignored) {
                // Non-numeric input — leave staged value unchanged
            }
        });

        this.addRenderableWidget(leappad_lanPortField);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void leappad_renderLanPortLabel(GuiGraphics graphics, int mouseX, int mouseY,
                                             float delta, CallbackInfo ci) {
        if (leappad_lanPortField == null) return;
        // Label above the field — same layout as Edit World screen
        graphics.drawString(
            this.font,
            "Leap! Pad LAN Port (auto-open)",
            leappad_lanPortField.getX(),
            leappad_lanPortField.getY() - 22,
            0xA0A0A0
        );
        // Warning in bright red — must be immediately obvious before typing a value
        graphics.drawString(
            this.font,
            "Warning: exposes world to network on launch",
            leappad_lanPortField.getX(),
            leappad_lanPortField.getY() - 12,
            0xFF5555
        );
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
