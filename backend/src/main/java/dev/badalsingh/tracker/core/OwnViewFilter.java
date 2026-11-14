package dev.badalsingh.tracker.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Removes requests that were caused by the sender looking at their own copy.
 *
 * <p>When the sender opens their Sent copy (or a bounce), Gmail's image proxy fetches the tracking pixel
 * exactly like it would for a recipient. The extension reports each such view; any request that arrives
 * shortly before or after a reported view is treated as the sender's own and ignored. The window is short,
 * so a real open a few minutes later still counts.
 */
public final class OwnViewFilter {
    /** The fetch can land just before the report reaches the server. */
    public static final Duration BEFORE = Duration.ofSeconds(30);
    /** Gmail may load the image a little after it renders the message. */
    public static final Duration AFTER = Duration.ofSeconds(90);

    private OwnViewFilter() {}

    public static boolean isOwn(Instant eventAt, Collection<Instant> ownViews) {
        if (eventAt == null || ownViews == null) return false;
        for (Instant v : ownViews) {
            if (!eventAt.isBefore(v.minus(BEFORE)) && !eventAt.isAfter(v.plus(AFTER))) return true;
        }
        return false;
    }

    public static List<EventInput> withoutOwn(List<EventInput> events, Collection<Instant> ownViews) {
        if (ownViews == null || ownViews.isEmpty()) return events;
        List<EventInput> kept = new ArrayList<>(events.size());
        for (EventInput e : events) if (!isOwn(e.at(), ownViews)) kept.add(e);
        return kept;
    }
}
