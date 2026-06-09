package elder.leapp.fabric.mixin;

// EditWorldScreenMixin.java
// Injects a "Leap! Pad LAN Port" text field into the Edit World screen.
//
// World path resolution:
//   EditWorldScreen stores a LevelStorageSource.LevelStorageAccess (field_24188)
//   which it uses internally to write world saves. We locate it by type via
//   getDeclaredFields() — the same approach used in the prototype builds — and
//   call getLevelPath(LevelResource.ROOT) on it directly.
//
//   We do NOT close this access. The screen owns it and will close it itself.
//   We borrow the reference only long enough to resolve the path.
//
//   This avoids @Shadow (Parchment field name unknown), constructor injection
//   (constructor takes an unmapped nested type), and any assumption about what
//   String fields exist on the screen.

import elder.leapp.LeapPadCommon;
import elder.leapp.config.WorldLanConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.nio.file.Path;

@Mixin(EditWorldScreen.class)
public abstract class EditWorldScreenMixin extends Screen {

    @Unique
    private EditBox leappad_lanPortField;

    @Unique
    private WorldLanConfig leappad_lanConfig;

    protected EditWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void leappad_addLanPortField(CallbackInfo ci) {
        Path worldSaveDir = leappad_resolveWorldSaveDir();
        if (worldSaveDir == null) return;

        leappad_lanConfig = WorldLanConfig.load(worldSaveDir);

        leappad_lanPortField = new EditBox(
            this.font,
            10, this.height - 48,
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
        // Label above the field
        graphics.drawString(
            this.font,
            "Leap! Pad LAN Port (auto-open)",
            leappad_lanPortField.getX(),
            leappad_lanPortField.getY() - 22,
            0xA0A0A0
        );
        // Warning below the label in bright red — must be immediately visible
        graphics.drawString(
            this.font,
            "Warning: exposes world to network on launch",
            leappad_lanPortField.getX(),
            leappad_lanPortField.getY() - 12,
            0xFF5555
        );
    }

    // Finds the LevelStorageAccess field on EditWorldScreen by type, borrows it
    // long enough to resolve the world save path, then releases the reference.
    // We do NOT close the access — the screen owns it.
    @Unique
    private Path leappad_resolveWorldSaveDir() {
        try {
            Class<?> screenClass = ((Object) this).getClass();
            // Walk up the class hierarchy in case the field is on a superclass,
            // though in 1.20.1 it is on EditWorldScreen itself.
            while (screenClass != null && screenClass != Object.class) {
                for (Field field : screenClass.getDeclaredFields()) {
                    if (LevelStorageSource.LevelStorageAccess.class
                            .isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        LevelStorageSource.LevelStorageAccess access =
                            (LevelStorageSource.LevelStorageAccess) field.get(this);
                        if (access != null) {
                            return access.getLevelPath(
                                net.minecraft.world.level.storage.LevelResource.ROOT
                            ).toAbsolutePath();
                        }
                    }
                }
                screenClass = screenClass.getSuperclass();
            }
        } catch (Exception e) {
            LeapPadCommon.LOGGER.warn(
                "[Leap! Pad] EditWorldScreenMixin: could not resolve world save dir: {}",
                e.getMessage()
            );
        }
        return null;
    }
}
