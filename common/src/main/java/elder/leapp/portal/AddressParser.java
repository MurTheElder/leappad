package elder.leapp.portal;

// AddressParser.java
// Takes the text content of a written book and extracts a valid host:port address from it.
// Returns null if no valid address is found — the caller sends "This book doesn't contain
// a valid address." and returns the book to the player.
//
// Lives in portal/ because it is only used by PortalRegistry for book-throw link writes.

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AddressParser {

    // Matches host:port patterns.
    // Host can be an IPv4 address (e.g. 123.456.789.0) or a hostname (e.g. my.server.net).
    // Port must be a 1-5 digit number.
    // The pattern is intentionally permissive — validation of the actual reachability
    // is done by the probe during the transfer sequence, not here.
    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
        "([a-zA-Z0-9._-]+):([0-9]{1,5})"
    );

    // Holds a successfully parsed address
    public static class ParsedAddress {
        public final String host;
        public final int port;
        public final String full; // "host:port" as a single string

        public ParsedAddress(String host, int port) {
            this.host = host;
            this.port = port;
            this.full = host + ":" + port;
        }
    }

    // Scans the given text for the first valid host:port address.
    // Returns null if none is found or if the port number is out of range.
    public static ParsedAddress parse(String bookText) {
        if (bookText == null || bookText.isBlank()) return null;

        Matcher matcher = ADDRESS_PATTERN.matcher(bookText);
        while (matcher.find()) {
            String host = matcher.group(1);
            String portStr = matcher.group(2);

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                continue; // Shouldn't happen given the regex, but be safe
            }

            // Valid port range: 1–65535
            if (port < 1 || port > 65535) continue;

            // Reject obviously invalid hosts (e.g. bare numbers without dots)
            if (host.matches("[0-9]+")) continue;

            return new ParsedAddress(host, port);
        }

        return null; // No valid address found in the book
    }
}
