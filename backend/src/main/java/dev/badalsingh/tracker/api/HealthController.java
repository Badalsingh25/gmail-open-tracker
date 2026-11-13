package dev.badalsingh.tracker.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Unauthenticated liveness probe, present in every mode (for the container healthcheck and uptime monitors). */
@RestController
public class HealthController {
    private final String mode;

    public HealthController(@Value("${tracker.mode:all}") String mode) { this.mode = mode; }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "gmail-open-tracker", "mode", mode);
    }
}
