package elder.leapp.fabric.ui;

// ProfileSelectorScreen.java
// Opened during the transfer sequence step 5 for direct connect / server list paths.
// Lets the player choose which character to use on the target world before
// the connection proceeds.
//
// After the player makes a selection, this screen calls
// ProfileManager.setActiveProfile() and signals the orchestrator to continue
// the sequence. If the player cancels, the transfer is aborted.

import elder.leapp.profile.CharacterProfile;
import elder.leapp.profile.ProfileManager;
import elder.leapp.portal.PortalRegistry;
import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ProfileSelectorScreen extends Screen {

    private final Screen parent;
    private final String playerUuid;
    private final String targetAddress;

    private List<CharacterProfile> profileList;
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP = 64;
    private static final int LIST_BOTTOM_MARGIN = 64;

    private Button joinButton;

    public ProfileSelectorScreen(Screen parent, String playerUuid, String targetAddress) {
        super(Component.literal("Choose a Character"));
        this.parent = parent;
        this.playerUuid = playerUuid;
        this.targetAddress = targetAddress;
    }

    @Override
    protected void init() {
        profileList = ProfileManager.getAllProfiles();

        // Pre-select the last profile used for this address
        String lastUsed = ProfileManager.getLastUsedProfileForAddress(targetAddress);
        if (lastUsed != null) {
            for (int i = 0; i < profileList.size(); i++) {
                if (profileList.get(i).profileUuid.equals(lastUsed)) {
                    selectedIndex = i;
                    break;
                }
            }
        }

        int centerX = this.width / 2;
        int buttonY = this.height - 48;

        joinButton = Button.builder(
            Component.literal("Join"),
            button -> joinWithProfile()
        ).bounds(centerX - 154, buttonY, 100, 20).build();
        joinButton.active = selectedIndex >= 0;
        this.addRenderableWidget(joinButton);

        this.addRenderableWidget(Button.builder(
            Component.literal("New Profile"),
            button -> this.minecraft.setScreen(new ProfileNameInputScreen(
                this, "", false,
                name -> {
                    CharacterProfile created = ProfileManager.createProfile(name);
                    profileList = ProfileManager.getAllProfiles();
                    // Auto-select the new profile
                    for (int i = 0; i < profileList.size(); i++) {
                        if (profileList.get(i).profileUuid.equals(created.profileUuid)) {
                            selectedIndex = i;
                            break;
                        }
                    }
                    joinWithProfile();
                },
                ProfileManager.getAllProfiles() // S2: duplicate name check
            ))
        ).bounds(centerX - 50, buttonY, 100, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.literal("No Profile"),
            button -> joinWithNoProfile()
        ).bounds(centerX + 54, buttonY, 100, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.literal("Cancel"),
            button -> cancel()
        ).bounds(centerX - 50, buttonY + 24, 100, 20).build());
    }

    // -------------------------------------------------------
    // Actions
    // -------------------------------------------------------

    private void joinWithProfile() {
        if (selectedIndex < 0 || selectedIndex >= profileList.size()) return;
        CharacterProfile profile = profileList.get(selectedIndex);
        ProfileManager.setActiveProfile(profile.profileUuid);
        ProfileManager.updateLastUsedAddress(targetAddress);
        this.minecraft.setScreen(null);
        resumeSequence();
    }

    private void joinWithNoProfile() {
        ProfileManager.setActiveProfile(null);
        this.minecraft.setScreen(null);
        resumeSequence();
    }

    private void cancel() {
        this.minecraft.setScreen(parent);
        TransferOrchestrator.cancelFromProfileSelector(playerUuid);
    }

    // Signal the orchestrator that the profile has been chosen and the
    // sequence can continue from where it paused at AWAITING_PROFILE.
    private void resumeSequence() {
        TransferOrchestrator.onProfileSelected(playerUuid);
    }

    // -------------------------------------------------------
    // Input
    // -------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int relY = (int) mouseY - LIST_TOP;
            int listBottom = this.height - LIST_BOTTOM_MARGIN;
            if (relY >= 0 && mouseY < listBottom) {
                int clicked = (relY / ENTRY_HEIGHT) + scrollOffset;
                if (clicked >= 0 && clicked < profileList.size()) {
                    selectedIndex = clicked;
                    joinButton.active = true;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int visible = (this.height - LIST_BOTTOM_MARGIN - LIST_TOP) / ENTRY_HEIGHT;
        int maxScroll = Math.max(0, profileList.size() - visible);
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - delta));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 264) { selectedIndex = Math.min(profileList.size() - 1, selectedIndex + 1); joinButton.active = selectedIndex >= 0; return true; }
        if (keyCode == 265) { selectedIndex = Math.max(0, selectedIndex - 1); joinButton.active = selectedIndex >= 0; return true; }
        if (keyCode == 257 && selectedIndex >= 0) { joinWithProfile(); return true; }
        if (keyCode == 256) { cancel(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // -------------------------------------------------------
    // Render
    // -------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
            Component.literal("Joining: " + resolveDisplayLabel()),
            this.width / 2, 32, 0xA0A0A0);

        int listBottom = this.height - LIST_BOTTOM_MARGIN;
        int visible = (listBottom - LIST_TOP) / ENTRY_HEIGHT;

        if (profileList.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.literal("No profiles yet. Click New Profile to create one."),
                this.width / 2, this.height / 2 - 10, 0xA0A0A0);
        } else {
            for (int i = 0; i < visible; i++) {
                int idx = i + scrollOffset;
                if (idx >= profileList.size()) break;
                CharacterProfile profile = profileList.get(idx);
                int entryY = LIST_TOP + (i * ENTRY_HEIGHT);
                int textY = entryY + (ENTRY_HEIGHT - this.font.lineHeight) / 2;

                if (idx == selectedIndex) {
                    graphics.fill(4, entryY, this.width - 4, entryY + ENTRY_HEIGHT, 0x80FFFFFF);
                }

                String label = profile.label.isEmpty() ? "" : "  §7" + profile.label + "§r";
                int color = idx == selectedIndex ? 0xFFFF00 : 0xFFFFFF;
                graphics.drawString(this.font, profile.displayName + label, 8, textY, color);
            }
        }

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // Returns the nickname for the target address if one is set, otherwise
    // the raw address. Used in the subtitle line so players see "Dave's World"
    // instead of an IP when a nickname has been configured for that portal.
    private String resolveDisplayLabel() {
        String nick = PortalRegistry.getNicknameForAddress(targetAddress);
        return (nick != null && !nick.isEmpty()) ? nick : targetAddress;
    }
}
