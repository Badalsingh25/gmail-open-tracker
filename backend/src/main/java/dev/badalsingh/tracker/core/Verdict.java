package dev.badalsingh.tracker.core;

import java.time.Instant;
import java.util.List;

public record Verdict(
        int score,
        String status,
        int pixelEvents,
        int clickEvents,
        Instant firstOpenAt,
        Instant lastOpenAt,
        List<String> evidence,
        boolean notMeasurable) {

    public static Verdict none() {
        return new Verdict(0, Status.NO_ACTIVITY, 0, 0, null, null, List.of(), false);
    }
}
