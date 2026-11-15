package dev.badalsingh.tracker.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the sender's own looking at a sent mail from counting as the recipient's open.
 *
 * <p>When the sender opens their Sent copy, Gmail's image proxy can fetch the tracking pixel exactly like it does
 * for a recipient, and the two cannot be told apart. The extension reports each own view, and requests are then
 * treated by distance from the report:
 * <ul>
 *   <li><b>Hard window</b> (a few seconds around the view): the request is the sender's own and is ignored.</li>
 *   <li><b>Soft window</b> (a little wider): it might be the recipient, so it is never discarded. It still counts,
 *       but cannot lift the verdict above "possibly opened" on its own.</li>
 *   <li>Anything outside both windows counts normally.</li>
 * </ul>
 */
public final class OwnViewFilter {
    /** The fetch can land just before the report reaches the server. */
    public static final Duration BEFORE = Duration.ofSeconds(15);
    /** Gmail may load the image a little after it renders the message. */
    public static final Duration AFTER = Duration.ofSeconds(20);
    public static final Duration SOFT_BEFORE = Duration.ofSeconds(30);
    public static final Duration SOFT_AFTER = Duration.ofSeconds(120);

    public static final String NEAR_NOTE = "Arrived near your own view of the email, so it counts as possible only";

    public enum Zone { NONE, SOFT, HARD }

    private OwnViewFilter() {}

    public static Zone zone(Instant eventAt, Collection<Instant> ownViews) {
        if (eventAt == null || ownViews == null) return Zone.NONE;
        Zone result = Zone.NONE;
        for (Instant v : ownViews) {
            if (!eventAt.isBefore(v.minus(BEFORE)) && !eventAt.isAfter(v.plus(AFTER))) return Zone.HARD;
            if (!eventAt.isBefore(v.minus(SOFT_BEFORE)) && !eventAt.isAfter(v.plus(SOFT_AFTER))) result = Zone.SOFT;
        }
        return result;
    }

    public static boolean isOwn(Instant eventAt, Collection<Instant> ownViews) {
        return zone(eventAt, ownViews) == Zone.HARD;
    }

    public static boolean isNear(Instant eventAt, Collection<Instant> ownViews) {
        return zone(eventAt, ownViews) == Zone.SOFT;
    }

    public static List<EventInput> withoutOwn(List<EventInput> events, Collection<Instant> ownViews) {
        if (ownViews == null || ownViews.isEmpty()) return events;
        List<EventInput> kept = new ArrayList<>(events.size());
        for (EventInput e : events) if (!isOwn(e.at(), ownViews)) kept.add(e);
        return kept;
    }

    /** The verdict for a recipient, taking the sender's own views into account. */
    public static Verdict evaluate(OpenScorer scorer, List<EventInput> events, Collection<Instant> ownViews) {
        if (ownViews == null || ownViews.isEmpty()) return scorer.evaluate(events);
        List<EventInput> kept = withoutOwn(events, ownViews);
        Verdict full = scorer.evaluate(kept);
        if (!Status.OPENED.equals(full.status())) return full; // clicks stay ENGAGED; weaker verdicts need no cap

        List<EventInput> strict = new ArrayList<>(kept.size());
        for (EventInput e : kept) if (!isNear(e.at(), ownViews)) strict.add(e);
        if (strict.size() == kept.size()) return full;

        Verdict withoutNear = scorer.evaluate(strict);
        if (Status.OPENED.equals(withoutNear.status()) || Status.ENGAGED.equals(withoutNear.status())) return full;

        ScoringConfig c = scorer.config();
        int score = Math.min(full.score(), c.openedThreshold() - 1);
        Set<String> evidence = new LinkedHashSet<>();
        evidence.add(NEAR_NOTE);
        evidence.addAll(full.evidence());
        return new Verdict(score, score >= c.possibleThreshold() ? Status.POSSIBLY_OPENED : Status.AUTOMATED_ONLY,
                full.pixelEvents(), full.clickEvents(), full.firstOpenAt(), full.lastOpenAt(), List.copyOf(evidence), full.notMeasurable());
    }
}
