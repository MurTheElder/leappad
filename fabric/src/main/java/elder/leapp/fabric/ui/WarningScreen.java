package elder.leapp.fabric.ui;

// WarningScreen.java
// Shown when a player tries to connect to a world that is online but does not
// have Leap! Pad installed. The player can choose to proceed (vanilla join,
// no profile dat sent, no mirror portal built) or cancel.
//
// The destination label passed to this screen is already resolved by the caller
// (LeapPadFabricClient.showNoLeapPad) — it is the portal's nickname if one is
// set, or the raw target address if not. This screen never does nickname lookups
// itself; it just displays what it was given.
//
// Proceed → TransferOrchestrator.onNoLeapPadConfirmed(playerUuid)
// Cancel  → TransferOrchestrator.cancelFromWarningScreen(playerUuid)

import elder.leapp.transfer.TransferOrchestrator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class WarningScreen extends Screen {

    private final Screen parent;
    private final String playerUuid;
    // Pre-resolved destination label: nickname if set, raw address otherwise.
    private final String destinationLabel;

    public WarningScreen(Screen parent, String playerUuid, String destinationLabel) {
        super(Component.literal("No Leap! Pad on Destination"));
        this.parent         = parent;
        this.playerUuid     = playerUuid;
        this.destinationLabel = destinationLabel;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonY = this.height / 2 + 20;

        // Proceed — vanilla join with no dat, no portal
        this.addRenderableWidget(Button.builder(
            Component.literal("Proceed"),
            button -> proceed()
        ).bounds(centerX - 105, buttonY, 100, 20).build());

        // Cancel — discard session, return to previous screen
        this.addRenderableWidget(Button.builder(
            Component.literal("Cancel"),
            button -> cancel()
        ).bounds(centerX + 5, buttonY, 100, 20).build());
    }

    // -------------------------------------------------------
    // Actions
    // -------------------------------------------------------

    private void proceed() {
        this.minecraft.setScreen(null);
        TransferOrchestrator.onNoLeapPadConfirmed(playerUuid);
    }

    private void cancel() {
        this.minecraft.setScreen(parent);
        TransferOrchestrator.cancelFromWarningScreen(playerUuid);
    }

    // -------------------------------------------------------
    // Input
    // -------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter confirms, Escape cancels
        if (keyCode == 257) { proceed(); return true; }
        if (keyCode == 256) { cancel(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // -------------------------------------------------------
    // Render
    // -------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 48, 0xFFFFFF);

        // Destination label
        graphics.drawCenteredString(this.font,
            Component.literal(destinationLabel),
            this.width / 2, this.height / 2 - 30, 0xFFD700
        );

        // Warning body
        graphics.drawCenteredString(this.font,
            Component.literal("This world doesn't have Leap! Pad installed."),
            this.width / 2, this.height / 2 - 10, 0xC0C0C0
        );
        graphics.drawCenteredString(this.font,
            Component.literal("Your character profile will not be sent."),
            this.width / 2, this.height / 2 + 4, 0xC0C0C0
        );

        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
