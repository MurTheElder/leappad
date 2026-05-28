package elder.leapp.probe;

// PortBindingCache.java
// The runtime state of all port bindings and offset configurations.
// Populated from LeapPadConfig on world start.
// All /leappad port commands read and write this cache exclusively —
// the config file is never modified at runtime.
// Tracks: currently bound ports, active listening offsets, active outbound check offsets.

import elder.leapp.config.LeapPadConfig;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class PortBindingCache {

    // The game port this world is running on.
    // Set once on world start and never changes.
    private static int gamePort = 25565;

    // Ports currently bound for listening (the actual port numbers, not offsets).
    private static final Set<Integer> boundPorts = new HashSet<>();

    // Offsets currently active for inbound probe listening.
    // A probe arriving on gamePort + offset is answered.
    private static final Set<Integer> activeListenOffsets = new HashSet<>();

    // Offsets currently active for outbound probe sending.
    // When probing a target, we try gamePort + offset for each active outbound offset.
    private static final Set<Integer> activeOutboundOffsets = new HashSet<>();

    // Called on world start to populate the cache from config.
    // Sets up the primary probe port offset and all fallback offsets.
    public static void init(int worldGamePort) {
        gamePort = worldGamePort;
        boundPorts.clear();
        activeListenOffsets.clear();
        activeOutboundOffsets.clear();

        // Primary probe offset goes in both directions by default
        activeListenOffsets.add(LeapPadConfig.probePortOffset);
        activeOutboundOffsets.add(LeapPadConfig.probePortOffset);

        // Fallback offsets are outbound-only by default
        for (int fallback : LeapPadConfig.probePortFallbacks) {
            activeOutboundOffsets.add(fallback);
        }
    }

    // -------------------------------------------------------
    // Port binding — used by /leappad open and /leappad close
    // -------------------------------------------------------

    public static void bindPort(int port) {
        boundPorts.add(port);
    }

    public static void unbindPort(int port) {
        boundPorts.remove(port);
    }

    public static void bindAllConfigPorts() {
        // Bind the primary probe port
        boundPorts.add(gamePort + LeapPadConfig.probePortOffset);
        // Bind all fallback ports
        for (int fallback : LeapPadConfig.probePortFallbacks) {
            boundPorts.add(gamePort + fallback);
        }
    }

    public static void unbindAllPorts() {
        boundPorts.clear();
    }

    // -------------------------------------------------------
    // Offset management — used by /leappad offset commands
    // -------------------------------------------------------

    public static void addListenOffset(int offset) {
        activeListenOffsets.add(offset);
    }

    public static void removeListenOffset(int offset) {
        activeListenOffsets.remove(offset);
    }

    public static void addOutboundOffset(int offset) {
        activeOutboundOffsets.add(offset);
    }

    public static void removeOutboundOffset(int offset) {
        activeOutboundOffsets.remove(offset);
    }

    // -------------------------------------------------------
    // Read accessors
    // -------------------------------------------------------

    public static int getGamePort() {
        return gamePort;
    }

    public static Set<Integer> getBoundPorts() {
        return Collections.unmodifiableSet(boundPorts);
    }

    public static Set<Integer> getActiveListenOffsets() {
        return Collections.unmodifiableSet(activeListenOffsets);
    }

    public static Set<Integer> getActiveOutboundOffsets() {
        return Collections.unmodifiableSet(activeOutboundOffsets);
    }

    public static int getBoundPortCount() {
        return boundPorts.size();
    }
}
