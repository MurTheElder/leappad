package elder.leapp.fabric.mixin;

// ConnectScreenInvoker.java
// @Invoker mixin that exposes ConnectScreen.startConnecting() so it can be
// called from FabricReconnectHandler.
//
// startConnecting is the Mojang-mapped name for method_36877 — the public static
// factory that constructs a ConnectScreen, opens it, and initiates the connection.
// It is public, so this invoker is technically not needed for access purposes,
// but using @Invoker keeps the call pattern consistent and avoids importing
// ConnectScreen directly in FabricReconnectHandler.
//
// Parameters (Mojang 1.20.1):
//   parent      — screen to return to on failure (pass mc.screen)
//   minecraft   — the Minecraft client instance
//   serverAddress  — parsed server address
//   serverData  — server data (name, ip, isLan)
//   isQuickPlay — false for all normal Leap! Pad connections
//
// Usage (from FabricReconnectHandler):
//   ConnectScreenInvoker.invokeStartConnecting(mc.screen, mc, address, serverData, false);

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConnectScreen.class)
public interface ConnectScreenInvoker {

    @Invoker("startConnecting")
    static void invokeStartConnecting(Screen parent, Minecraft minecraft,
                                       ServerAddress serverAddress, ServerData serverData,
                                       boolean isQuickPlay) {
        throw new AssertionError("Mixin @Invoker stub — should never execute directly");
    }
}
