package dev.badalsingh.tracker.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    @Test
    void allowsBurstThenBlocksThenRefills() {
        AtomicLong clock = new AtomicLong();
        RateLimiter limiter = new RateLimiter(3, 1.0, 1000, clock::get);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));       // independent key
        clock.addAndGet(2_000_000_000L);            // +2s => 2 tokens
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
    }

    @Test
    void memoryIsBounded() {
        AtomicLong clock = new AtomicLong();
        RateLimiter limiter = new RateLimiter(1, 0, 100, clock::get);
        for (int i = 0; i < 1000; i++) assertTrue(limiter.tryAcquire("k" + i));
    }
}
