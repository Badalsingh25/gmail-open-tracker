package dev.badalsingh.tracker.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenScorerTest {
    private final OpenScorer scorer = new OpenScorer(ScoringConfig.defaults());
    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    private static EventInput pixel(long afterSendSeconds, String ua, String ip) {
        return new EventInput(T0.plusSeconds(afterSendSeconds), new Features(Features.Kind.PIXEL, "GET", ua, ip, afterSendSeconds));
    }
    private static EventInput click(long afterSendSeconds) {
        return new EventInput(T0.plusSeconds(afterSendSeconds), new Features(Features.Kind.CLICK, "GET", Features.UA_OTHER, Features.IP_OTHER, afterSendSeconds));
    }

    @Test
    void noEventsMeansNoActivity() {
        assertEquals(Status.NO_ACTIVITY, scorer.evaluate(List.of()).status());
    }

    @Test
    void lateGmailProxyFetchFromGoogleIsOpened() {
        Verdict v = scorer.evaluate(List.of(pixel(3600, Features.UA_GMAIL_PROXY, Features.IP_GOOGLE)));
        assertEquals(Status.OPENED, v.status());
        assertTrue(v.score() >= 70);
        assertNotNull(v.firstOpenAt());
    }

    @Test
    void immediateFetchIsAutomated() {
        Verdict v = scorer.evaluate(List.of(pixel(2, Features.UA_GMAIL_PROXY, Features.IP_GOOGLE)));
        assertEquals(Status.AUTOMATED_ONLY, v.status());
        assertNull(v.firstOpenAt());
    }

    @Test
    void appleRelayPrefetchIsNotAnOpenAndIsFlaggedNotMeasurable() {
        Verdict v = scorer.evaluate(List.of(pixel(5000, Features.UA_OTHER, Features.IP_APPLE)));
        assertEquals(Status.AUTOMATED_ONLY, v.status());
        assertTrue(v.notMeasurable());
    }

    @Test
    void scannerAndHeadTrafficNeverCountsHoweverMuchOfIt() {
        List<EventInput> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            events.add(pixel(10_000 + i * 60L, Features.UA_SCANNER, Features.IP_OTHER));
            events.add(new EventInput(T0.plusSeconds(20_000 + i * 60L), new Features(Features.Kind.PIXEL, "HEAD", Features.UA_OTHER, Features.IP_OTHER, 20_000)));
        }
        assertEquals(Status.AUTOMATED_ONLY, scorer.evaluate(events).status());
    }

    @Test
    void gmailUserAgentFromNonGoogleAddressIsSuspicious() {
        Verdict v = scorer.evaluate(List.of(pixel(3600, Features.UA_GMAIL_PROXY, Features.IP_OTHER)));
        assertTrue(v.score() < 70);
        assertNotEquals(Status.OPENED, v.status());
    }

    @Test
    void realClickIsEngaged() {
        Verdict v = scorer.evaluate(List.of(click(900)));
        assertEquals(Status.ENGAGED, v.status());
        assertTrue(v.score() >= 97);
    }

    @Test
    void clickRightAfterSendingIsAScannerNotEngagement() {
        Verdict v = scorer.evaluate(List.of(click(4)));
        assertNotEquals(Status.ENGAGED, v.status());
        assertNotEquals(Status.OPENED, v.status());
    }

    @Test
    void burstIsOneFetchNotCorroboration() {
        EventInput a = pixel(100, Features.UA_OTHER, Features.IP_OTHER);
        EventInput b = new EventInput(T0.plusSeconds(100).plusMillis(500), a.features());
        EventInput c = new EventInput(T0.plusSeconds(100).plusMillis(900), a.features());
        int single = scorer.evaluate(List.of(a)).score();
        assertEquals(single, scorer.evaluate(List.of(a, b, c)).score());
    }

    @Test
    void twoModerateFetchesFarApartBecomeAPersonReReading() {
        Verdict one = scorer.evaluate(List.of(pixel(100, Features.UA_OTHER, Features.IP_OTHER)));
        Verdict two = scorer.evaluate(List.of(pixel(100, Features.UA_OTHER, Features.IP_OTHER),
                pixel(100 + 4 * 3600, Features.UA_OTHER, Features.IP_OTHER)));
        assertTrue(two.score() > one.score());
        assertEquals(Status.OPENED, two.status());
        assertTrue(two.evidence().stream().anyMatch(s -> s.contains("again")));
    }

    @Test
    void eventOrderDoesNotMatter() {
        List<EventInput> events = List.of(pixel(5000, Features.UA_GMAIL_PROXY, Features.IP_GOOGLE),
                pixel(2, Features.UA_GMAIL_PROXY, Features.IP_GOOGLE), click(7000));
        List<EventInput> reversed = new ArrayList<>(events);
        java.util.Collections.reverse(reversed);
        assertEquals(scorer.evaluate(events), scorer.evaluate(reversed));
    }

    @Test
    void outlookDesktopFetchCountsAsAnOpen() {
        Verdict v = scorer.evaluate(List.of(pixel(7200, Features.UA_OUTLOOK, Features.IP_OTHER)));
        assertEquals(Status.OPENED, v.status());
    }

    @Test
    void everyEventHasAReason() {
        for (String ua : new String[]{Features.UA_GMAIL_PROXY, Features.UA_YAHOO_PROXY, Features.UA_OUTLOOK, Features.UA_SCANNER, Features.UA_OTHER, Features.UA_NONE}) {
            EventScore s = scorer.scoreEvent(new Features(Features.Kind.PIXEL, "GET", ua, Features.IP_OTHER, 1000));
            assertFalse(s.reason().isBlank());
            assertTrue(s.score() >= 0 && s.score() <= 100);
        }
    }
}
