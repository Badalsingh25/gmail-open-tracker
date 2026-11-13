package dev.badalsingh.tracker.ingest;

import dev.badalsingh.tracker.core.Features;
import dev.badalsingh.tracker.core.RateLimiter;
import dev.badalsingh.tracker.model.TrackedRecipient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Optional;

/**
 * Public endpoints hit by recipients' mail clients. Guiding rule: never break what the recipient sees.
 * A bad token, a database hiccup or a rate limit must still return the image / perform the redirect;
 * the only thing that changes is whether the request is recorded.
 */
@RestController
@ConditionalOnExpression("'${tracker.mode:all}' != 'api'")
public class IngestController {
    private static final Logger log = LoggerFactory.getLogger(IngestController.class);
    private static final byte[] TRANSPARENT_GIF = Base64.getDecoder().decode("R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs=");
    private static final String NO_CACHE = "no-store, no-cache, must-revalidate, max-age=0, private";

    private final IngestService ingest;
    private final RateLimiter limiter;
    private final String clientIpHeader;

    public IngestController(IngestService ingest, RateLimiter limiter,
                            @Value("${tracker.client-ip-header:X-Forwarded-For}") String clientIpHeader) {
        this.ingest = ingest;
        this.limiter = limiter;
        this.clientIpHeader = clientIpHeader;
    }

    // No `produces`: a scanner sending an odd Accept header must still get the image (and be recorded).
    // Spring also routes HEAD here; HEAD is scored as an automated checker.
    @GetMapping("/t/{token}.gif")
    public ResponseEntity<byte[]> pixel(@PathVariable String token, HttpServletRequest request) {
        try {
            RequestSignals signals = signals(request);
            if (limiter.tryAcquire(rateKey(signals, request))) {
                ingest.authenticate(token).ifPresent(r -> ingest.record(r, Features.Kind.PIXEL, null, signals));
            }
        } catch (Exception e) {
            log.warn("Could not record pixel request: {}", e.toString());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, NO_CACHE)
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.EXPIRES, "0")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.IMAGE_GIF)
                .body(TRANSPARENT_GIF);
    }

    @GetMapping("/l/{token}/{linkId}")
    public ResponseEntity<Void> click(@PathVariable String token, @PathVariable String linkId, HttpServletRequest request) {
        Optional<TrackedRecipient> recipient;
        Optional<String> target;
        try {
            long id = Long.parseLong(linkId);
            recipient = ingest.authenticate(token);
            target = recipient.flatMap(r -> ingest.resolveLink(r, id));
            if (target.isEmpty()) return ResponseEntity.notFound().build();
            try {
                RequestSignals signals = signals(request);
                if (limiter.tryAcquire(rateKey(signals, request))) {
                    ingest.record(recipient.get(), Features.Kind.CLICK, id, signals);
                }
            } catch (Exception e) {
                log.warn("Could not record click: {}", e.toString()); // still redirect
            }
        } catch (NumberFormatException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.warn("Click lookup failed: {}", e.toString());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target.get())
                .header(HttpHeaders.CACHE_CONTROL, NO_CACHE)
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private RequestSignals signals(HttpServletRequest request) {
        return new RequestSignals(request.getMethod(), request.getHeader(HttpHeaders.USER_AGENT), clientIp(request));
    }

    private static String rateKey(RequestSignals signals, HttpServletRequest request) {
        return signals.clientIp() != null ? signals.clientIp() : request.getRemoteAddr();
    }

    /**
     * Best-effort source address, used in memory only. Configure tracker.client-ip-header to match your
     * proxy: X-Forwarded-For (Caddy/nginx; the last entry is the one our own proxy appended),
     * CF-Connecting-IP (behind Cloudflare), or "remote" (no proxy).
     */
    private String clientIp(HttpServletRequest request) {
        if ("remote".equalsIgnoreCase(clientIpHeader)) return request.getRemoteAddr();
        String value = request.getHeader(clientIpHeader);
        if (value == null || value.isBlank()) return request.getRemoteAddr();
        int comma = value.lastIndexOf(',');
        return (comma < 0 ? value : value.substring(comma + 1)).trim();
    }
}
