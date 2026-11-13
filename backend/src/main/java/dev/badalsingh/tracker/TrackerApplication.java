package dev.badalsingh.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One jar, three modes (tracker.mode):
 * <ul>
 *   <li>{@code ingest}: public pixel/click endpoints only. Tiny, no dashboard, no API key.</li>
 *   <li>{@code api}: authenticated dashboard API only.</li>
 *   <li>{@code all}: both (local development).</li>
 * </ul>
 * Run ingest and api as separate processes against the same database for production.
 */
@SpringBootApplication
@EnableScheduling
public class TrackerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrackerApplication.class, args);
    }
}
