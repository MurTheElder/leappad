package elder.leapp;

// LeapMixinTargets.java
// Central library of all Mixin @Invoker and @Accessor interfaces for the
// Leap! Pad mod suite. Companion mods (leapforward, leapbackwards) depend on
// leappad-common and get access to these for free.
//
// When a Minecraft version update requires mapping changes, update the
// @Invoker value strings here and all mods in the suite pick up the change
// on their next build.
//
// Currently empty — ConnectScreenInvoker was removed after startConnecting
// was confirmed public in 1.20.1 and called directly from FabricReconnectHandler.
// Add @Invoker interfaces here as new Mixin targets are identified.

public class LeapMixinTargets {
    // Reserved for future @Invoker and @Accessor nested interfaces.
}
