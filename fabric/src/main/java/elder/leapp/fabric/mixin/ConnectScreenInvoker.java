package elder.leapp.fabric.mixin;

// ConnectScreenInvoker.java
// @Invoker mixin that exposes ConnectScreen's private connect() method so it can
// be called from outside the class without violating Java access rules.
//
// In 1.20.1 under Mojang mappings, ConnectScreen.connect(Minecraft, ServerAddress, ServerData)
// is a private static method. Java prevents calling it directly from outside the class.
// Mixin's @Invoker generates a bridge that bypasses the access restriction at the
// bytecode level — the same mechanism that allows @Inject to target private methods.
//
// For private STATIC methods, the @Invoker interface method must be marked static.
// Java interfaces don't allow abstract static methods, so we use a default stub body
// that Mixin replaces at transform time. The stub body is never actually called.
//
// Usage (from releaseGate and portal trigger):
//   ConnectScreenInvoker.connect(mc, address, serverData);

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConnectScreen.class)
public interface ConnectScreenInvoker {

    // Exposes the private static connect(Minecraft, ServerAddress, ServerData).
    // The default body is a stub — Mixin replaces it with the real call at transform time.
    @Invoker("connect")
    static void connect(Minecraft minecraft, ServerAddress serverAddress, ServerData serverData) {
        throw new AssertionError("Mixin @Invoker stub — should never be called directly");
    }
}
