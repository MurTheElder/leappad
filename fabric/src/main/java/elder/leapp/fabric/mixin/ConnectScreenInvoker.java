package elder.leapp.fabric.mixin;

// ConnectScreenInvoker.java
// @Invoker mixin that exposes method_36877 on ConnectScreen.
//
// method_36877 is the static factory that constructs a ConnectScreen,
// opens it, and initiates the multiplayer connection. It is the entry
// point vanilla's own server list and direct-connect screens use.
//
// remap = false: we reference the method by its intermediary name rather
// than trying to resolve it through Mojang mappings. This is the same
// approach used by both old versions of this project and avoids the
// access and signature issues that arise from Mojang-mapped names in the
// 1.20.1 build environment.
//
// The method is static, so the interface method is also static with a
// stub body. Mixin replaces the stub at class-load time — the throw is
// never reached in normal operation.
//
// Usage (from FabricReconnectHandler):
//   ConnectScreenInvoker.invokeConnect(mc.screen, mc, address, serverData, false);

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConnectScreen.class)
public interface ConnectScreenInvoker {

    @Invoker(value = "method_36877", remap = false)
    static void invokeConnect(Screen parent, Minecraft minecraft,
                               ServerAddress serverAddress, ServerData serverData,
                               boolean quickPlay) {
        throw new AssertionError("Mixin @Invoker stub — should never execute directly");
    }
}
