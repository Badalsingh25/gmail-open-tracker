package dev.badalsingh.tracker.core;

/**
 * Tunable thresholds. Defaults are starting heuristics, not measured values: tune them with the
 * calibration harness against real mail clients (see calibration/CALIBRATION.md).
 */
public record ScoringConfig(
        int earlySeconds,        // pixel/click this soon after sending is almost certainly prefetch/scan/preview
        int settleSeconds,       // after this long a pixel fetch is more likely a real open than a prefetch
        long burstMillis,        // requests closer than this are one fetch, not independent evidence
        int openedThreshold,     // verdict score >= this => OPENED
        int possibleThreshold,   // verdict score >= this => POSSIBLY_OPENED
        int noiseFloor,          // event groups scoring below this contribute nothing
        int repeatGapSeconds) {  // two credible opens at least this far apart => a person re-reading

    public static ScoringConfig defaults() {
        return new ScoringConfig(30, 300, 2000, 70, 35, 15, 3600);
    }
}
