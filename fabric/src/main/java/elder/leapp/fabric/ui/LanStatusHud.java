package elder.leapp.fabric.ui;

// LanStatusHud.java
// Renders a persistent "[Leap! Pad] Open on port XXXXX" overlay in the
// top-left corner of the screen for OP-level players when the world has
// been auto-opened to LAN by the Leap! Pad LAN feature.
//
// Safety requirement: the host must always be able to see that their world
// is open and accepting connections. This overlay:
//   - Appears immediately after LAN auto-open fires in SERVER_STARTING
//   - Persists for the entire session
//   - Hides when the F3 debug screen is open (to avoid conflicting with debug info)
//   - Clears when /leappad close is run
//   - Is only visible to OP-level players (permission level >= 2)
//
// Registered via HudRenderCallback in LeapPadFabricClient.

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class LanStatusHud {

    // The port currently open, or -1 if not active.
    private static int activePort = -1;

    // Register the HUD render callback. Called once from LeapPadFabricClient.
    public static void register() {
        HudRenderCallback.EVENT.register(LanStatusHud::render);
    }

    // Called by LeapPadFabric after LAN auto-open succeeds.
    public static void setActive(int port) {
        activePort = port;
    }

    // Called when /leappad close runs or the world closes.
    public static void clear() {
        activePort = -1;
    }

    public static boolean isActive() {
        return activePort > 0;
    }

    private static void render(GuiGraphics graphics, float tickDelta) {
        if (activePort <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        // Only show to OP-level players
        if (!mc.player.hasPermissions(2)) return;

        // Hide when F3 debug screen is open
        if (mc.getDebugOverlay().showDebugScreen()) return;

        // Render in top-left corner: 4px padding from edges
        String message = "[Leap! Pad] Open on port " + activePort;
        graphics.drawString(
            mc.font,
            message,
            4, 4,
            0x55FF55  // Green — matches Minecraft's OP/system message colour
        );
    }
}
