package dev.badalsingh.tracker.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Parsers for the published IP lists. Tolerant: malformed lines are ignored. */
public final class IpListParser {
    private IpListParser() {}

    private static final Pattern GOOGLE_PREFIX = Pattern.compile("\"ipv[46]Prefix\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Apple iCloud Private Relay egress list: CSV lines like {@code 172.224.226.0/27,GB,GB-ENG,London,}.
     * Only the first column is used.
     */
    public static List<String> parseAppleEgressCsv(Stream<String> lines) {
        List<String> out = new ArrayList<>();
        lines.forEach(line -> {
            if (line == null) return;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return;
            int comma = trimmed.indexOf(',');
            String first = (comma < 0 ? trimmed : trimmed.substring(0, comma)).trim();
            if (first.indexOf('/') > 0) out.add(first);
        });
        return out;
    }

    /** Google {@code goog.json}: {@code {"prefixes":[{"ipv4Prefix":"34.80.0.0/15"},{"ipv6Prefix":"2600:1900::/35"}]}}. */
    public static List<String> parseGoogleJson(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) return out;
        Matcher m = GOOGLE_PREFIX.matcher(json);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }
}
