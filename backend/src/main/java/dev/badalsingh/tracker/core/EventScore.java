package dev.badalsingh.tracker.core;

/** Score (0-100: how likely this single request reflects a human) plus a plain-language reason. */
public record EventScore(int score, String reason) {}
