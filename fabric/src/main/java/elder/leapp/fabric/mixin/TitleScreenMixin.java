package elder.leapp.fabric.mixin;

// TitleScreenMixin.java
// Two responsibilities:
//
//   1. Injects the "Character Profiles" button into the title screen layout.
//      Clicking it opens the ProfileManager overlay.
//
//   2. Clears the active profile string in ProfileManager on every title screen render.
//      This covers all routes back to the main menu (disconnect, quit world, etc.)
//      and ensures the profile selector always re-prompts on the next connection.
//
//      Exception: one-cycle suppression flag prevents the clear immediately after
//      the profile selector closes, so the selection isn't wiped before it can be used.
//      The suppression flag lives locally in this Mixin — nothing else touches it.

import elder.leapp.profile.ProfileManager;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    // Suppression flag — set to true when the profile selector closes
    // so the next render cycle does NOT clear the active profile string.
    // Cleared back to false after one cycle.
    @Unique
    private boolean leappad_suppressClear = false;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    // Called every time the title screen initialises its buttons.
    // We add our "Character Profiles" button here so it appears alongside
    // the vanilla Singleplayer / Multiplayer buttons.
    @Inject(method = "init", at = @At("TAIL"))
    private void leappad_addProfilesButton(CallbackInfo ci) {
        // Position the button below the standard button row.
        // X: centred on screen. Y: 132 puts it just below the multiplayer button area.
        // Width 200, height 20 — standard Minecraft button dimensions.
        this.addRenderableWidget(Button.builder(
            Component.literal("Character Profiles"),
            button -> {
                // Set the suppression flag so the render cycle that fires
                // when this screen resumes after the overlay closes doesn't wipe the selection
                leappad_suppressClear = true;
                // Open the profile manager overlay
                // (ProfileScreen is implemented in the profile UI layer — wired in a later step)
                openProfileManager();
            })
            .bounds(this.width / 2 - 100, 132, 200, 20)
            .build()
        );
    }

    // Called every render frame while the title screen is active.
    // Clears the active profile string unless the suppression flag is set.
    @Inject(method = "render", at = @At("HEAD"))
    private void leappad_clearActiveProfile(
            net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY,
            float delta, CallbackInfo ci) {
        if (leappad_suppressClear) {
            // One-cycle suppression — clear the flag but do NOT clear the profile string
            leappad_suppressClear = false;
        } else {
            ProfileManager.clearActiveProfile();
        }
    }

    // Opens the profile manager overlay screen.
    // The title screen stays underneath — it does not re-render.
    @Unique
    private void openProfileManager() {
        // ProfileScreen is wired in the UI build step.
        // For now, log the action so we know the button fires correctly.
        elder.leapp.LeapPadCommon.LOGGER.info("[Leap! Pad] Character Profiles button clicked.");
        // minecraft.setScreen(new ProfileScreen(this)); — added when ProfileScreen is built
    }
}
