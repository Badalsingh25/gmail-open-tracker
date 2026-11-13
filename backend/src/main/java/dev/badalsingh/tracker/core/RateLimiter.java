package dev.badalsingh.tracker.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Per-key token bucket. Bounded memory: the table is cleared if it grows past {@code maxKeys}. */
public final class RateLimiter {
    private static final class Bucket { double tokens; long lastNanos; }

    private final double capacity;
    private final double refillPerNano;
    private final int maxKeys;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int capacity, double refillPerSecond, int maxKeys, LongSupplier nanoClock) {
        this.capacity = Math.max(1, capacity);
        this.refillPerNano = Math.max(0, refillPerSecond) / 1_000_000_000d;
        this.maxKeys = Math.max(100, maxKeys);
        this.nanoClock = nanoClock;
    }

    public RateLimiter(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, 50_000, System::nanoTime);
    }

    public boolean tryAcquire(String key) {
        if (buckets.size() > maxKeys) buckets.clear();
        Bucket bucket = buckets.computeIfAbsent(key == null ? "?" : key, k -> {
            Bucket b = new Bucket(); b.tokens = capacity; b.lastNanos = nanoClock.getAsLong(); return b;
        });
        synchronized (bucket) {
            long now = nanoClock.getAsLong();
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.lastNanos) * refillPerNano);
            bucket.lastNanos = now;
            if (bucket.tokens >= 1) { bucket.tokens -= 1; return true; }
            return false;
        }
    }
}
