package dev.badalsingh.tracker.ingest;

import dev.badalsingh.tracker.core.IpListParser;
import dev.badalsingh.tracker.core.IpRanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Holds the published address lists used to sanity-check who is fetching a pixel, and refreshes them
 * daily. If a download fails the previous list is kept; if none was ever loaded the Google check is
 * simply skipped (see Features.IP_UNCHECKED) and Apple falls back to its owned 17.0.0.0/8 block.
 */
@Component
@ConditionalOnExpression("'${tracker.mode:all}' != 'api'")
public class IpListHolder {
    private static final Logger log = LoggerFactory.getLogger(IpListHolder.class);
    private static final String APPLE_FALLBACK = "17.0.0.0/8";

    private volatile IpRanges apple = IpRanges.of(List.of(APPLE_FALLBACK));
    private volatile IpRanges google = IpRanges.EMPTY;

    private final boolean enabled;
    private final String appleUrl;
    private final String googleUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public IpListHolder(@Value("${tracker.ip-lists.enabled:true}") boolean enabled,
                        @Value("${tracker.ip-lists.apple-relay-url:https://mask-api.icloud.com/egress-ip-ranges.csv}") String appleUrl,
                        @Value("${tracker.ip-lists.google-url:https://www.gstatic.com/ipranges/goog.json}") String googleUrl) {
        this.enabled = enabled;
        this.appleUrl = appleUrl;
        this.googleUrl = googleUrl;
    }

    public IpRanges apple() { return apple; }
    public IpRanges google() { return google; }

    @Scheduled(initialDelayString = "${tracker.ip-lists.initial-delay-ms:15000}",
            fixedDelayString = "${tracker.ip-lists.refresh-ms:86400000}")
    public void refresh() {
        if (!enabled) return;
        try {
            HttpResponse<Stream<String>> response = http.send(request(appleUrl), HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() == 200) {
                    List<String> cidrs = new ArrayList<>(IpListParser.parseAppleEgressCsv(lines));
                    if (!cidrs.isEmpty()) {
                        cidrs.add(APPLE_FALLBACK);
                        apple = IpRanges.of(cidrs);
                        log.info("Loaded Apple relay list: {}", apple);
                    }
                } else log.warn("Apple relay list returned HTTP {}; keeping previous list", response.statusCode());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not refresh Apple relay list ({}); keeping previous list", e.toString());
        }
        try {
            HttpResponse<String> response = http.send(request(googleUrl), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                IpRanges parsed = IpRanges.of(IpListParser.parseGoogleJson(response.body()));
                if (!parsed.isEmpty()) { google = parsed; log.info("Loaded Google range list: {}", parsed); }
            } else log.warn("Google range list returned HTTP {}; keeping previous list", response.statusCode());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not refresh Google range list ({}); keeping previous list", e.toString());
        }
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build();
    }
}
