package dev.badalsingh.tracker.ingest;

/** Raw request details, used in memory to derive features and then dropped. Never persisted. */
public record RequestSignals(String method, String userAgent, String clientIp) {}
