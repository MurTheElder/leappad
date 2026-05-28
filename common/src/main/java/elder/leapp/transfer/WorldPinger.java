package elder.leapp.transfer;

// WorldPinger.java
// Makes the outbound TCP probe connection to a target world address.
// Reads PortBindingCache for which outbound offsets are active and tries them in order.
// Carries the client's explicit external IP and the Leap! Forward presence flag.
//
// Returns one of three states:
//   UNREACHABLE       — could not connect on any probe port
//   REACHABLE_NO_LP   — connected but target has no Leap! Pad
//   REACHABLE_HAS_LP  — connected and target has Leap! Pad; transfer key received

import elder.leapp.LeapPadCommon;
import elder.leapp.probe.PortBindingCache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class WorldPinger {

    // The three possible outcomes of a probe attempt
    public enum PingResult {
        UNREACHABLE,
        REACHABLE_NO_LP,
        REACHABLE_HAS_LP
    }

    // Holds a successful probe result including the transfer key from the host
    public static class PingOutcome {
        public final PingResult result;
        public final String transferKey; // Only set when result is REACHABLE_HAS_LP

        public PingOutcome(PingResult result, String transferKey) {
            this.result = result;
            this.transferKey = transferKey;
        }

        public PingOutcome(PingResult result) {
            this(result, null);
        }
    }

    // Probes the given host:port address.
    // clientIp — the client's explicit external IP (not assumed from socket)
    // leapForward — whether Leap! Forward is installed on this client
    // targetHost — hostname or IP of the target world
    // targetGamePort — the game port of the target world
    //
    // Tries the primary probe offset first, then fallback offsets in order.
    // Returns the first successful result, or UNREACHABLE if all fail.
    public static PingOutcome probe(String targetHost, int targetGamePort,
                                    String clientIp, boolean leapForward) {

        // Build the ordered list of ports to try: primary offset first, then fallbacks
        int primaryPort = targetGamePort + PortBindingCache.getGamePort(); // relative offset logic
        // Actually use the offsets directly against the target game port
        int[] offsetsToTry = buildOffsetArray();

        for (int offset : offsetsToTry) {
            int probePort = targetGamePort + offset;
            PingOutcome outcome = attemptProbe(targetHost, probePort, clientIp, leapForward);
            if (outcome.result != PingResult.UNREACHABLE) {
                return outcome;
            }
        }

        // All ports failed
        LeapPadCommon.LOGGER.info(
            "[Leap! Pad] All probe ports unreachable for {}:{}", targetHost, targetGamePort
        );
        return new PingOutcome(PingResult.UNREACHABLE);
    }

    // Attempts a single probe on the given port.
    // Returns UNREACHABLE on any connection failure.
    private static PingOutcome attemptProbe(String host, int port,
                                             String clientIp, boolean leapForward) {
        try (Socket socket = new Socket()) {
            // 3-second connection timeout — don't hang on unreachable hosts
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            // Send probe fields
            out.writeUTF(clientIp);
            out.writeBoolean(leapForward);
            out.flush();

            // Read response
            boolean reachable = in.readBoolean();
            boolean hasLeapPad = in.readBoolean();
            String transferKey = in.readUTF();

            if (!reachable) {
                return new PingOutcome(PingResult.UNREACHABLE);
            }
            if (!hasLeapPad) {
                return new PingOutcome(PingResult.REACHABLE_NO_LP);
            }
            return new PingOutcome(PingResult.REACHABLE_HAS_LP, transferKey);

        } catch (IOException e) {
            // Connection refused, timeout, or other network error — try next port
            LeapPadCommon.LOGGER.debug(
                "[Leap! Pad] Probe failed on port {}: {}", port, e.getMessage()
            );
            return new PingOutcome(PingResult.UNREACHABLE);
        }
    }

    // Builds the ordered array of offsets to try from PortBindingCache.
    // Primary offset goes first, then the rest in iteration order.
    private static int[] buildOffsetArray() {
        var offsets = PortBindingCache.getActiveOutboundOffsets();
        int[] result = new int[offsets.size()];
        int i = 0;
        for (int offset : offsets) {
            result[i++] = offset;
        }
        return result;
    }
}
