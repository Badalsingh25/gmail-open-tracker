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
        assertTrue(OwnViewFilter.isOwn(VIEW.minusSeconds(10), List.of(VIEW)));
        assertTrue(OwnViewFilter.isOwn(VIEW.plusSeconds(15), List.of(VIEW)));
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

    private static Features gmail(long offset) {
        return new Features(Features.Kind.PIXEL, "GET", Features.UA_GMAIL_PROXY, Features.IP_GOOGLE, offset);
    }

    @Test
    void nearbyRequestsAreSoftNotHard() {
        assertEquals(OwnViewFilter.Zone.HARD, OwnViewFilter.zone(VIEW.plusSeconds(10), List.of(VIEW)));
        assertEquals(OwnViewFilter.Zone.SOFT, OwnViewFilter.zone(VIEW.plusSeconds(60), List.of(VIEW)));
        assertEquals(OwnViewFilter.Zone.SOFT, OwnViewFilter.zone(VIEW.minusSeconds(25), List.of(VIEW)));
        assertEquals(OwnViewFilter.Zone.NONE, OwnViewFilter.zone(VIEW.plusSeconds(600), List.of(VIEW)));
    }

    @Test
    void openNearOwnViewIsCappedAtPossible() {
        OpenScorer scorer = new OpenScorer(ScoringConfig.defaults());
        List<EventInput> events = List.of(new EventInput(VIEW.plusSeconds(60), gmail(400)));
        Verdict plain = scorer.evaluate(events);
        Verdict v = OwnViewFilter.evaluate(scorer, events, List.of(VIEW));
        assertTrue(v.score() < 70);
        assertTrue(v.score() <= plain.score());
        if (Status.OPENED.equals(plain.status())) {
            assertEquals(Status.POSSIBLY_OPENED, v.status());
            assertEquals(OwnViewFilter.NEAR_NOTE, v.evidence().get(0));
        }
    }

    @Test
    void openAwayFromOwnViewKeepsFullVerdict() {
        OpenScorer scorer = new OpenScorer(ScoringConfig.defaults());
        List<EventInput> events = List.of(new EventInput(VIEW.plusSeconds(3600), gmail(3600)));
        assertEquals(scorer.evaluate(events).score(), OwnViewFilter.evaluate(scorer, events, List.of(VIEW)).score());
        assertEquals(scorer.evaluate(events).status(), OwnViewFilter.evaluate(scorer, events, List.of()).status());
    }
}
