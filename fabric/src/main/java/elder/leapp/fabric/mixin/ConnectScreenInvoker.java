package elder.leapp.fabric.mixin;

// ConnectScreenInvoker.java
// @Invoker mixin that exposes ConnectScreen's private instance connect() method
// so it can be called from outside the class without violating Java access rules.
//
// In 1.20.1 under Mojang mappings, ConnectScreen.connect(Minecraft, ServerAddress, ServerData)
// is a private instance method (method_2130 in intermediary). Mixin's @Invoker generates
// a proxy that bypasses the access restriction at the bytecode level — the same mechanism
// that allows @Inject to target private methods.
//
// Because this targets an INSTANCE method, the interface method is non-static.
// Usage requires casting a ConnectScreen instance to ConnectScreenInvoker:
//
//   if (mc.screen instanceof ConnectScreenInvoker invoker) {
//       invoker.invokeConnect(mc, address, serverData);
//   }
//
// This is valid for releaseGate(), where mc.screen is the ConnectScreen that is
// currently open. It is NOT valid for portal triggers, where no ConnectScreen
// exists yet — those use a new ConnectScreen instance directly.

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConnectScreen.class)
public interface ConnectScreenInvoker {

    // Exposes the private instance connect(Minecraft, ServerAddress, ServerData).
    // Non-static: called on a ConnectScreen instance cast to this interface.
    // Mixin replaces the body at transform time — the implementation here is never called.
    @Invoker("connect")
    void invokeConnect(Minecraft minecraft, ServerAddress serverAddress, ServerData serverData);
}
