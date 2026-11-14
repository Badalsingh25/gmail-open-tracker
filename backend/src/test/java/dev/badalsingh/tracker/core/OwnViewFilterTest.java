package dev.badalsingh.tracker.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OwnViewFilterTest {
    private static final Instant VIEW = Instant.parse("2026-11-14T06:00:00Z");

    @Test
    void requestsAroundAnOwnViewAreIgnored() {
        assertTrue(OwnViewFilter.isOwn(VIEW, List.of(VIEW)));
        assertTrue(OwnViewFilter.isOwn(VIEW.minusSeconds(20), List.of(VIEW)));
        assertTrue(OwnViewFilter.isOwn(VIEW.plusSeconds(60), List.of(VIEW)));
    }

    @Test
    void laterRealOpensStillCount() {
        assertFalse(OwnViewFilter.isOwn(VIEW.plusSeconds(300), List.of(VIEW)));
        assertFalse(OwnViewFilter.isOwn(VIEW.minusSeconds(120), List.of(VIEW)));
        assertFalse(OwnViewFilter.isOwn(VIEW, List.of()));
        assertFalse(OwnViewFilter.isOwn(null, List.of(VIEW)));
    }

    @Test
    void withoutOwnKeepsOnlyTheRest() {
        Features f = new Features(Features.Kind.PIXEL, "GET", Features.UA_GMAIL_PROXY, Features.IP_GOOGLE, 100);
        List<EventInput> events = List.of(new EventInput(VIEW.plusSeconds(5), f), new EventInput(VIEW.plusSeconds(3600), f));
        List<EventInput> kept = OwnViewFilter.withoutOwn(events, List.of(VIEW));
        assertEquals(1, kept.size());
        assertEquals(VIEW.plusSeconds(3600), kept.get(0).at());
        assertSame(events, OwnViewFilter.withoutOwn(events, List.of()));
    }
}
