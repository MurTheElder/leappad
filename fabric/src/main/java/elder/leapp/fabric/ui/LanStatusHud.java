package elder.leapp.fabric.ui;

// LanStatusHud.java
// Renders a persistent "[Leap! Pad] Open on port XXXXX" overlay in the
// top-left corner for OP-level players when the world has been auto-opened
// to LAN by the Leap! Pad LAN feature.
//
// Hides when F3 is open — checked via mc.options.renderDebug (public boolean).
// Clears on /leappad close or world stop.

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class LanStatusHud {

    private static int activePort = -1;

    public static void register() {
        HudRenderCallback.EVENT.register(LanStatusHud::render);
    }

    public static void setActive(int port) {
        activePort = port;
    }

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

        // Hide when F3 debug screen is open — mc.options.renderDebug is the
        // public boolean that controls debug overlay visibility in 1.20.1
        if (mc.options.renderDebug) return;

        String message = "[Leap! Pad] Open on port " + activePort;
        graphics.drawString(mc.font, message, 4, 4, 0x55FF55);
    }
}
