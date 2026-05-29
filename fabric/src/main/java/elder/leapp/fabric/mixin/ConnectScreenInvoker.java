package elder.leapp.fabric.mixin;

// ConnectScreenInvoker.java
// @Invoker mixin that exposes ConnectScreen's internal connection factory method
// so it can be called from outside the class.
//
// The target method is method_36877 in Mojang intermediary — the static factory
// that constructs a ConnectScreen, opens it, and initiates the network connection.
// Both old versions of this project (V1 and V2) used this exact method for all
// connection triggers. We reference it by its intermediary name rather than its
// Mojang-mapped name to avoid the access and signature issues that arise from
// how Mojang mappings expose it in the 1.20.1 build environment.
//
// Because the target is a STATIC method, the @Invoker interface method is also
// static with a stub body. Mixin replaces the stub at class-load time before it
// can ever be called — the throw is unreachable in normal operation.
//
// Parameters match method_36877 exactly:
//   parent      — the screen to return to if the connection fails (mc.screen is correct)
//   mc          — the Minecraft client instance
//   address     — the parsed server address
//   serverData  — the server data object (name, ip, isLan)
//   quickPlay   — false for all normal Leap! Pad connections
//
// Usage:
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

    // Targets method_36877 directly by intermediary name, bypassing Mojang mapping
    // access issues. Mixin resolves the intermediary name at class-load time.
    @Invoker("method_36877")
    static void invokeConnect(Screen parent, Minecraft minecraft,
                              ServerAddress serverAddress, ServerData serverData,
                              boolean quickPlay) {
        throw new AssertionError("Mixin @Invoker stub — should never execute directly");
    }
}
