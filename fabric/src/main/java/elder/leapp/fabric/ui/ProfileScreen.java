package elder.leapp.fabric.ui;

// ProfileScreen.java
// The profile manager screen, opened from the title screen "Character Profiles" button.
// Shows all saved profiles in a scrollable list. Lets the player select, edit,
// delete, and create profiles. Does not interact with the transfer sequence.
//
// Layout:
//   - Title at top
//   - Scrollable profile list occupying the upper 75% of the screen
//   - Button row at the bottom: Select, Edit, Delete, New, Close
//   - Active profile marked with [ACTIVE] prefix
//   - Empty state message when no profiles exist

import elder.leapp.profile.CharacterProfile;
import elder.leapp.profile.ProfileManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ProfileScreen extends Screen {

    private final Screen parent;

    // List state
    private List<CharacterProfile> profileList;
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    // Layout constants
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP_MARGIN = 48;
    private static final int LIST_BOTTOM_MARGIN = 60;
    private static final int BUTTON_ROW_Y_FROM_BOTTOM = 36;
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_SPACING = 4;

    // Buttons — held as fields so we can toggle active/inactive
    private Button selectButton;
    private Button editButton;
    private Button deleteButton;

    public ProfileScreen(Screen parent) {
        super(Component.literal("Character Profiles"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        refreshList();

        int buttonRowY = this.height - BUTTON_ROW_Y_FROM_BOTTOM;
        // Five buttons centred: Select, Edit, Delete, New, Close
        int totalWidth = (BUTTON_WIDTH * 5) + (BUTTON_SPACING * 4);
        int startX = (this.width - totalWidth) / 2;

        selectButton = Button.builder(
            Component.literal("Select"),
            button -> selectProfile()
        ).bounds(startX, buttonRowY, BUTTON_WIDTH, 20).build();
        this.addRenderableWidget(selectButton);

        editButton = Button.builder(
            Component.literal("Edit"),
            button -> editProfile()
        ).bounds(startX + (BUTTON_WIDTH + BUTTON_SPACING), buttonRowY, BUTTON_WIDTH, 20).build();
        this.addRenderableWidget(editButton);

        deleteButton = Button.builder(
            Component.literal("Delete"),
            button -> confirmDelete()
        ).bounds(startX + (BUTTON_WIDTH + BUTTON_SPACING) * 2, buttonRowY, BUTTON_WIDTH, 20).build();
        this.addRenderableWidget(deleteButton);

        this.addRenderableWidget(Button.builder(
            Component.literal("New"),
            button -> newProfile()
        ).bounds(startX + (BUTTON_WIDTH + BUTTON_SPACING) * 3, buttonRowY, BUTTON_WIDTH, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.literal("Close"),
            button -> this.minecraft.setScreen(parent)
        ).bounds(startX + (BUTTON_WIDTH + BUTTON_SPACING) * 4, buttonRowY, BUTTON_WIDTH, 20).build());

        updateButtonStates();
    }

    // -------------------------------------------------------
    // Actions
    // -------------------------------------------------------

    private void selectProfile() {
        if (selectedIndex < 0 || selectedIndex >= profileList.size()) return;
        CharacterProfile profile = profileList.get(selectedIndex);
        ProfileManager.setActiveProfile(profile.profileUuid);
        this.minecraft.setScreen(parent);
    }

    private void editProfile() {
        if (selectedIndex < 0 || selectedIndex >= profileList.size()) return;
        CharacterProfile profile = profileList.get(selectedIndex);
        this.minecraft.setScreen(new ProfileNameInputScreen(
            this, profile.displayName, true,
            newName -> {
                profile.displayName = newName;
                // Re-save via ProfileManager — easiest way to trigger disk write
                // is to re-create with same UUID and overwrite
                refreshList();
                updateButtonStates();
            }
        ));
    }

    private void confirmDelete() {
        if (selectedIndex < 0 || selectedIndex >= profileList.size()) return;
        CharacterProfile profile = profileList.get(selectedIndex);
        this.minecraft.setScreen(new ConfirmScreen(
            confirmed -> {
                if (confirmed) {
                    ProfileManager.deleteProfile(profile.profileUuid);
                    selectedIndex = -1;
                    refreshList();
                }
                this.minecraft.setScreen(this);
                updateButtonStates();
            },
            Component.literal("Delete Profile"),
            Component.literal("Delete \"" + profile.displayName + "\"? This cannot be undone.")
        ));
    }

    private void newProfile() {
        this.minecraft.setScreen(new ProfileNameInputScreen(
            this, "", false,
            name -> {
                ProfileManager.createProfile(name);
                refreshList();
                // Auto-select the newly created profile
                for (int i = 0; i < profileList.size(); i++) {
                    if (profileList.get(i).displayName.equals(name)) {
                        selectedIndex = i;
                        break;
                    }
                }
                updateButtonStates();
            }
        ));
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private void refreshList() {
        profileList = ProfileManager.getAllProfiles();
        // Clamp selection
        if (selectedIndex >= profileList.size()) selectedIndex = profileList.size() - 1;
    }

    private void updateButtonStates() {
        boolean hasSelection = selectedIndex >= 0 && selectedIndex < profileList.size();
        if (selectButton != null) selectButton.active = hasSelection;
        if (editButton != null)   editButton.active   = hasSelection;
        if (deleteButton != null) deleteButton.active = hasSelection;
    }

    private int listTop() { return LIST_TOP_MARGIN; }
    private int listBottom() { return this.height - LIST_BOTTOM_MARGIN; }
    private int visibleEntries() { return (listBottom() - listTop()) / ENTRY_HEIGHT; }

    // -------------------------------------------------------
    // Input
    // -------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int relY = (int) mouseY - listTop();
            if (relY >= 0 && mouseY < listBottom()) {
                int clicked = (relY / ENTRY_HEIGHT) + scrollOffset;
                if (clicked >= 0 && clicked < profileList.size()) {
                    selectedIndex = clicked;
                    updateButtonStates();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int maxScroll = Math.max(0, profileList.size() - visibleEntries());
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - delta));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Arrow keys navigate the list
        if (keyCode == 264) { // DOWN
            selectedIndex = Math.min(profileList.size() - 1, selectedIndex + 1);
            updateButtonStates();
            return true;
        }
        if (keyCode == 265) { // UP
            selectedIndex = Math.max(0, selectedIndex - 1);
            updateButtonStates();
            return true;
        }
        if (keyCode == 257 && selectedIndex >= 0) { // ENTER
            selectProfile();
            return true;
        }
        if (keyCode == 256) { // ESCAPE
            this.minecraft.setScreen(parent);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // -------------------------------------------------------
    // Render
    // -------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title,
            this.width / 2, 16, 0xFFFFFF);

        String activeUuid = ProfileManager.getActiveProfileUuid();

        if (profileList.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal("No profiles yet. Click New to create one."),
                this.width / 2, this.height / 2 - 20, 0xA0A0A0);
        } else {
            int visible = visibleEntries();
            for (int i = 0; i < visible; i++) {
                int idx = i + scrollOffset;
                if (idx >= profileList.size()) break;

                CharacterProfile profile = profileList.get(idx);
                int entryY = listTop() + (i * ENTRY_HEIGHT);
                int textY = entryY + (ENTRY_HEIGHT - this.font.lineHeight) / 2;

                // Highlight selected entry
                if (idx == selectedIndex) {
                    graphics.fill(4, entryY, this.width - 4, entryY + ENTRY_HEIGHT, 0x80FFFFFF);
                }

                // Active profile prefix
                String prefix = profile.profileUuid.equals(activeUuid) ? "[ACTIVE] " : "";
                String label = profile.label.isEmpty() ? "" : "  §7" + profile.label + "§r";
                String display = prefix + profile.displayName + label;

                int textColor = idx == selectedIndex ? 0xFFFF00 : 0xFFFFFF;
                graphics.drawString(this.font, display, 8, textY, textColor);
            }
        }

        // Scroll indicator if needed
        if (profileList.size() > visibleEntries()) {
            int maxScroll = profileList.size() - visibleEntries();
            graphics.drawString(this.font,
                (scrollOffset + 1) + "/" + profileList.size(),
                this.width - 30, listTop(), 0x808080);
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
