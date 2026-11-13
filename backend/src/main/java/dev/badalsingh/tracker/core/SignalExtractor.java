package dev.badalsingh.tracker.core;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Turns raw request details into {@link Features}. The raw values never leave this class. */
public final class SignalExtractor {
    // Deliberately excludes "microsoft office": Outlook desktop fetches images with that User-Agent
    // and that is a genuine open. Verify against real traffic using the calibration harness.
    private static final List<String> SCANNER_MARKERS = List.of(
            "barracuda", "mimecast", "proofpoint", "safelinks", "zscaler", "symantec", "messagelabs",
            "trendmicro", "trend micro", "forcepoint", "sophos", "fireeye", "ironport", "cisco",
            "bot", "crawler", "spider", "preview", "curl/", "wget/", "python-requests", "python-urllib",
            "go-http-client", "java/", "okhttp", "headlesschrome", "phantomjs", "slackbot", "facebookexternalhit");

    private final Supplier<IpRanges> apple;
    private final Supplier<IpRanges> google;

    public SignalExtractor(Supplier<IpRanges> appleRelay, Supplier<IpRanges> google) {
        this.apple = appleRelay;
        this.google = google;
    }

    public Features extract(Features.Kind kind, String method, String userAgent, String clientIp, long offsetSeconds) {
        return new Features(kind, method == null ? "GET" : method.toUpperCase(Locale.ROOT),
                classifyUserAgent(userAgent), classifyIp(clientIp), offsetSeconds);
    }

    static String classifyUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return Features.UA_NONE;
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("googleimageproxy") || ua.contains("via ggpht.com")) return Features.UA_GMAIL_PROXY;
        if (ua.contains("yahoomailproxy")) return Features.UA_YAHOO_PROXY;
        for (String marker : SCANNER_MARKERS) if (ua.contains(marker)) return Features.UA_SCANNER;
        if (ua.contains("microsoft outlook") || ua.contains("microsoft office")) return Features.UA_OUTLOOK;
        return Features.UA_OTHER;
    }

    String classifyIp(String ip) {
        if (ip == null || ip.isBlank()) return Features.IP_NONE;
        if (apple.get().contains(ip)) return Features.IP_APPLE;
        IpRanges g = google.get();
        if (g.isEmpty()) return Features.IP_UNCHECKED;
        return g.contains(ip) ? Features.IP_GOOGLE : Features.IP_OTHER;
    }
}
