package elder.leapp.fabric.ui;

// ProfileNameInputScreen.java
// Simple text-input screen for entering or editing a profile name.
// Used by ProfileScreen (new and edit), ProfileSelectorScreen (new),
// and PortalProfileSelectorScreen (new).
//
// The caller provides an initial value (empty for new, existing name for edit)
// and a Consumer<String> callback. On confirm the callback fires with the
// trimmed name. On cancel the callback is not called and the parent screen
// is restored.

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class ProfileNameInputScreen extends Screen {

    private final Screen parent;
    private final String initialValue;
    private final Consumer<String> onConfirm;
    private final boolean isEdit;
    // S2: Profile list for duplicate name detection. Null = no check performed.
    private final java.util.List<elder.leapp.profile.CharacterProfile> existingProfiles;

    private EditBox nameField;
    private Button confirmButton;

    // isEdit=false → title "New Profile", isEdit=true → title "Edit Profile"
    // existingProfiles: pass ProfileManager.getAllProfiles() to enable duplicate blocking.
    // Pass null to skip duplicate checking (e.g. for contexts where it isn't needed).
    public ProfileNameInputScreen(Screen parent, String initialValue,
                                   boolean isEdit, Consumer<String> onConfirm,
                                   java.util.List<elder.leapp.profile.CharacterProfile> existingProfiles) {
        super(Component.literal(isEdit ? "Edit Profile" : "New Profile"));
        this.parent = parent;
        this.initialValue = initialValue;
        this.isEdit = isEdit;
        this.onConfirm = onConfirm;
        this.existingProfiles = existingProfiles;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Name input field — centred, 200px wide
        nameField = new EditBox(
            this.font,
            centerX - 100, centerY - 20,
            200, 20,
            Component.literal("Profile name")
        );
        nameField.setMaxLength(32);
        nameField.setValue(initialValue);
        nameField.setHint(Component.literal("Profile name"));
        // S2: Disable confirm if blank or if name is already taken (case-insensitive).
        // On edit, allow the same name as the current value (no false duplicate).
        nameField.setResponder(text -> {
            String trimmed = text.trim();
            boolean blank = trimmed.isEmpty();
            boolean duplicate = existingProfiles != null && existingProfiles.stream()
                .anyMatch(p -> p.displayName.equalsIgnoreCase(trimmed)
                               && !trimmed.equalsIgnoreCase(initialValue));
            confirmButton.active = !blank && !duplicate;
            nameField.setHint(duplicate
                ? Component.literal("Name already in use")
                : Component.literal("Profile name"));
        });
        this.addRenderableWidget(nameField);

        // Confirm button
        confirmButton = Button.builder(
            Component.literal(isEdit ? "Save" : "Create"),
            button -> confirm()
        ).bounds(centerX - 102, centerY + 10, 100, 20).build();
        confirmButton.active = !initialValue.trim().isEmpty() &&
            (existingProfiles == null || existingProfiles.stream()
                .noneMatch(p -> p.displayName.equalsIgnoreCase(initialValue.trim())));
        this.addRenderableWidget(confirmButton);

        // Cancel button
        this.addRenderableWidget(Button.builder(
            Component.literal("Cancel"),
            button -> this.minecraft.setScreen(parent)
        ).bounds(centerX + 2, centerY + 10, 100, 20).build());

        // Focus the text field immediately
        this.setInitialFocus(nameField);
    }

    private void confirm() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) return;
        this.minecraft.setScreen(parent);
        onConfirm.accept(name);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter key triggers confirm
        if (keyCode == 257 || keyCode == 335) { // ENTER or NUMPAD_ENTER
            confirm();
            return true;
        }
        // Escape returns to parent
        if (keyCode == 256) {
            this.minecraft.setScreen(parent);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        // Title
        graphics.drawCenteredString(
            this.font, this.title,
            this.width / 2, this.height / 2 - 48, 0xFFFFFF
        );
        // Field label
        graphics.drawString(
            this.font, "Profile name",
            this.width / 2 - 100, this.height / 2 - 32, 0xA0A0A0
        );
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
