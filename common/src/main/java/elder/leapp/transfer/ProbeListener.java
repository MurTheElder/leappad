package elder.leapp.transfer;

// ProbeListener.java
// Listens on configured probe ports for incoming TCP probes from other Leap! Pad clients.
// When a probe arrives, it reads the client's IP and Leap! Forward flag,
// generates a transfer key UUID mapped to that client IP,
// and sends back a probe response.
//
// Bind failures are logged but non-fatal — the world continues even if a port is unavailable.
// The listener runs on a background thread and shuts down cleanly when the world closes.

import elder.leapp.LeapPadCommon;
import elder.leapp.network.LeapPadPackets;
import elder.leapp.probe.PortBindingCache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProbeListener {

    // Maps client IP → transfer key UUID for sessions currently in progress.
    // ConcurrentHashMap because probe handling runs on background threads.
    private static final Map<String, String> pendingTransferKeys = new ConcurrentHashMap<>();

    // Thread pool for handling probe connections — one thread per active probe.
    private static ExecutorService executor;

    // ServerSocket instances — one per bound probe port.
    // Kept so they can be closed cleanly on shutdown.
    private static final Map<Integer, ServerSocket> openSockets = new ConcurrentHashMap<>();

    // Set to false to signal all listener threads to stop.
    private static volatile boolean running = false;

    // Called from LeapPadFabric.onInitialize() on world start.
    // Opens a listener on each configured probe port.
    public static void start() {
        if (running) return;
        running = true;
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "LeapPad-ProbeListener");
            t.setDaemon(true); // Dies with the JVM if not cleanly stopped
            return t;
        });

        // Start a listener for each active listen offset
        for (int offset : PortBindingCache.getActiveListenOffsets()) {
            int port = PortBindingCache.getGamePort() + offset;
            openListenerOnPort(port);
        }
    }

    // Opens a ServerSocket on the given port and starts accepting connections.
    // Logs a warning and returns cleanly if the port is unavailable.
    public static void openListenerOnPort(int port) {
        executor.submit(() -> {
            ServerSocket server;
            try {
                server = new ServerSocket(port);
                openSockets.put(port, server);
                LeapPadCommon.LOGGER.info("[Leap! Pad] Probe listener open on port {}", port);
            } catch (IOException e) {
                LeapPadCommon.LOGGER.warn(
                    "[Leap! Pad] Could not bind probe listener on port {} — port may be in use. " +
                    "Other ports will still work. ({})", port, e.getMessage()
                );
                return;
            }

            // Accept connections in a loop until stop() is called
            while (running) {
                try {
                    Socket client = server.accept();
                    // Handle each probe on its own thread so we don't block the accept loop
                    executor.submit(() -> handleProbe(client));
                } catch (IOException e) {
                    // accept() throws when the server socket is closed — that's expected on shutdown
                    if (running) {
                        LeapPadCommon.LOGGER.warn("[Leap! Pad] Probe listener error on port {}: {}", port, e.getMessage());
                    }
                }
            }
        });
    }

    // Handles a single incoming probe connection.
    // Reads the probe packet, generates a transfer key, sends the response.
    private static void handleProbe(Socket client) {
        try (client) {
            client.setSoTimeout(5000); // 5-second read timeout — don't hang on bad connections

            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            // Read probe fields manually — this is a raw TCP connection, not a game protocol connection
            String clientIp = in.readUTF();
            boolean leapForward = in.readBoolean();

            LeapPadCommon.LOGGER.info(
                "[Leap! Pad] Probe received from {} (Leap! Forward: {})", clientIp, leapForward
            );

            // Generate a fresh transfer key for this session
            String transferKey = UUID.randomUUID().toString();

            // Store it mapped to the client IP so TransferOrchestrator can validate it later
            pendingTransferKeys.put(clientIp, transferKey);

            // Send back: reachable=true, hasLeapPad=true, transferKey
            out.writeBoolean(true);  // reachable
            out.writeBoolean(true);  // hasLeapPad
            out.writeUTF(transferKey);
            out.flush();

        } catch (IOException e) {
            LeapPadCommon.LOGGER.warn("[Leap! Pad] Error handling probe: {}", e.getMessage());
        }
    }

    // Called on world close. Shuts down all listeners cleanly.
    public static void stop() {
        running = false;
        for (ServerSocket socket : openSockets.values()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
        openSockets.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
        LeapPadCommon.LOGGER.info("[Leap! Pad] Probe listeners stopped.");
    }

    // Called by TransferOrchestrator to validate that an incoming transfer key
    // matches what was sent to that client IP.
    // Returns true and removes the key if it matches; false otherwise.
    public static boolean validateAndConsumeKey(String clientIp, String transferKey) {
        String stored = pendingTransferKeys.get(clientIp);
        if (stored != null && stored.equals(transferKey)) {
            pendingTransferKeys.remove(clientIp);
            return true;
        }
        return false;
    }
}
