package dev.badalsingh.tracker.core;

import java.time.Instant;

public record EventInput(Instant at, Features features) {}
