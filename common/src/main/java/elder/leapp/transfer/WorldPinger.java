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
//
// S1 fix: Removed dead variable 'primaryPort' (was computed but never used, and
// the computation was wrong — it added the local game port offset to the target port).
// buildOffsetArray() now guarantees the primary offset (from LeapPadConfig.probePortOffset)
// is tried first, with remaining offsets following in iteration order.

import elder.leapp.LeapPadCommon;
import elder.leapp.config.LeapPadConfig;
import elder.leapp.probe.PortBindingCache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Set;

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
    // clientIp    — the client's explicit external IP (not assumed from socket)
    // leapForward — whether Leap! Forward is installed on this client
    // targetHost  — hostname or IP of the target world
    // targetGamePort — the game port of the target world
    //
    // Tries the primary probe offset first, then fallback offsets in order.
    // Returns the first successful result, or UNREACHABLE if all fail.
    public static PingOutcome probe(String targetHost, int targetGamePort,
                                    String clientIp, boolean leapForward) {

        // S1 fix: buildOffsetArray() now guarantees primary offset is first.
        // Dead 'primaryPort' variable removed.
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
            DataInputStream  in  = new DataInputStream(socket.getInputStream());

            // Send probe fields
            out.writeUTF(clientIp);
            out.writeBoolean(leapForward);
            out.flush();

            // Read response
            boolean reachable   = in.readBoolean();
            boolean hasLeapPad  = in.readBoolean();
            String  transferKey = in.readUTF();

            if (!reachable)   return new PingOutcome(PingResult.UNREACHABLE);
            if (!hasLeapPad)  return new PingOutcome(PingResult.REACHABLE_NO_LP);
            return new PingOutcome(PingResult.REACHABLE_HAS_LP, transferKey);

        } catch (IOException e) {
            // Connection refused, timeout, or other network error — try next port
            LeapPadCommon.LOGGER.debug(
                "[Leap! Pad] Probe failed on port {}: {}", port, e.getMessage()
            );
            return new PingOutcome(PingResult.UNREACHABLE);
        }
    }

    // S1 fix: Builds the ordered array of offsets to try.
    // The primary offset (LeapPadConfig.probePortOffset) is always placed first.
    // Remaining offsets from PortBindingCache follow in their natural iteration order.
    // If the primary is not in the cache set (shouldn't happen, but guarded), it is
    // still placed first and the cache set is used as-is for the remainder.
    private static int[] buildOffsetArray() {
        int primary = LeapPadConfig.probePortOffset;
        Set<Integer> all = PortBindingCache.getActiveOutboundOffsets();

        // Slot 0 is always the primary. Remaining slots hold every other offset.
        int[] result = new int[Math.max(all.size(), 1)];
        result[0] = primary;
        int i = 1;
        for (int offset : all) {
            if (offset != primary) {
                if (i < result.length) {
                    result[i++] = offset;
                }
            }
        }

        // If the primary was not in the set, the array may be over-allocated by 1.
        // Trim to the actual number of entries written.
        return (i < result.length && result[i] == 0 && i > 0)
            ? Arrays.copyOf(result, i)
            : result;
    }
}
