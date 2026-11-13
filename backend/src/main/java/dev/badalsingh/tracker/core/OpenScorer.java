package dev.badalsingh.tracker.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scores tracking requests and combines them into a per-recipient verdict.
 *
 * <p>Principle: every request is weak, independent evidence. Each one gets a 0-100 score from what
 * we can observe (who fetched it, from where, how long after sending, whether it was a link click).
 * Requests arriving together are one fetch. Credible evidence is combined as a noisy-OR, so one
 * strong signal (a click, a late Gmail-proxy fetch) or several moderate ones raise confidence, while
 * a pile of scanner noise does not. Repeat opens hours apart are strong evidence of a person.
 *
 * <p>This stays a probabilistic estimate. It cannot see blocked images, and Apple Mail Privacy
 * Protection hides real opens; those cases are reported as {@code notMeasurable}, not guessed.
 */
public final class OpenScorer {
    private final ScoringConfig config;

    public OpenScorer(ScoringConfig config) { this.config = config; }

    public ScoringConfig config() { return config; }

    /** Score one request in isolation. */
    public EventScore scoreEvent(Features f) {
        if ("HEAD".equals(f.method())) return new EventScore(2, "HEAD request: a link/image checker, not a mail client");
        if (Features.UA_SCANNER.equals(f.uaClass())) {
            return new EventScore(f.kind() == Features.Kind.CLICK ? 5 : 3, "Security scanner or bot user-agent");
        }

        if (f.kind() == Features.Kind.CLICK) {
            if (f.offsetSeconds() < config.earlySeconds()) {
                return new EventScore(20, "Link followed within " + config.earlySeconds() + "s of sending: probably a delivery-time link scan");
            }
            return new EventScore(92, "Link clicked");
        }

        // Pixel fetch. Timing is the main discriminator: prefetch is immediate, reading is not.
        int base;
        String timing;
        if (f.offsetSeconds() < config.earlySeconds()) { base = 8; timing = "right after sending (compose preview, your own view, or a delivery scan)"; }
        else if (f.offsetSeconds() < config.settleSeconds()) { base = 50; timing = "shortly after sending"; }
        else { base = 72; timing = "well after sending"; }

        if (Features.IP_APPLE.equals(f.ipClass())) {
            return new EventScore(Math.min(base, 10), "Apple Mail Privacy Protection prefetch: Apple loads images for the user, so this is not a real open");
        }

        String who = "image fetch";
        switch (f.uaClass()) {
            case Features.UA_GMAIL_PROXY -> {
                if (Features.IP_OTHER.equals(f.ipClass())) {
                    return new EventScore(Math.min(base, 30), "Claims to be Gmail's image proxy but the address is not Google's: possible replay or spoof");
                }
                base += Features.IP_GOOGLE.equals(f.ipClass()) ? 12 : 8;
                who = "Gmail image proxy fetch";
            }
            case Features.UA_YAHOO_PROXY -> { base += 8; who = "Yahoo image proxy fetch"; }
            case Features.UA_OUTLOOK -> { base += 8; who = "Outlook client image fetch"; }
            case Features.UA_NONE -> { base -= 10; who = "image fetch with no user-agent"; }
            default -> who = "image fetch by an unrecognised client";
        }
        int score = Math.max(0, Math.min(95, base));
        return new EventScore(score, capitalise(who) + " " + timing);
    }

    /** Combine all of a recipient's events (any order) into one verdict. */
    public Verdict evaluate(List<EventInput> events) {
        if (events == null || events.isEmpty()) return Verdict.none();

        List<Group> pixelGroups = group(events, Features.Kind.PIXEL);
        List<Group> clickGroups = group(events, Features.Kind.CLICK);
        int pixelEvents = (int) events.stream().filter(e -> e.features().kind() == Features.Kind.PIXEL).count();
        int clickEvents = events.size() - pixelEvents;

        List<Group> all = new ArrayList<>(pixelGroups);
        all.addAll(clickGroups);

        double pNotOpened = 1.0;
        for (Group g : all) {
            if (g.score >= config.noiseFloor()) pNotOpened *= 1.0 - g.score / 100.0;
        }
        int score = (int) Math.round(100.0 * (1.0 - pNotOpened));

        Set<String> evidence = new LinkedHashSet<>();
        all.stream().sorted(Comparator.comparingInt((Group g) -> g.score).reversed())
                .limit(5).forEach(g -> evidence.add(g.reason + " (" + g.score + ")"));

        int bestClick = clickGroups.stream().mapToInt(g -> g.score).max().orElse(0);

        // Pattern: two credible pixel fetches hours apart look like a person returning to the mail.
        List<Group> credible = pixelGroups.stream().filter(g -> g.score >= 40)
                .sorted(Comparator.comparing(g -> g.at)).toList();
        if (credible.size() >= 2) {
            Duration span = Duration.between(credible.get(0).at, credible.get(credible.size() - 1).at);
            if (span.getSeconds() >= config.repeatGapSeconds()) {
                score = Math.max(score, 90);
                evidence.add("Opened again " + describe(span) + " later: typical of a person re-reading");
            }
        }

        String status;
        if (bestClick >= 80) { status = Status.ENGAGED; score = Math.max(score, 97); }
        else if (score >= config.openedThreshold()) status = Status.OPENED;
        else if (score >= config.possibleThreshold()) status = Status.POSSIBLY_OPENED;
        else status = Status.AUTOMATED_ONLY;

        Instant first = null, last = null;
        for (Group g : all) {
            if (g.score < config.possibleThreshold()) continue;
            if (first == null || g.at.isBefore(first)) first = g.at;
            if (last == null || g.at.isAfter(last)) last = g.at;
        }

        boolean notMeasurable = clickEvents == 0 && events.stream()
                .allMatch(e -> Features.IP_APPLE.equals(e.features().ipClass()));
        if (notMeasurable) evidence.add("Every request came from Apple's relay: this recipient's real opens cannot be measured");

        return new Verdict(score, status, pixelEvents, clickEvents, first, last, List.copyOf(evidence), notMeasurable);
    }

    private record Group(Instant at, int score, String reason) {}

    /** Requests of one kind closer together than burstMillis are one fetch: keep the strongest. */
    private List<Group> group(List<EventInput> events, Features.Kind kind) {
        List<EventInput> sorted = events.stream().filter(e -> e.features().kind() == kind)
                .sorted(Comparator.comparing(EventInput::at)).toList();
        List<Group> groups = new ArrayList<>();
        Instant previous = null;
        Group current = null;
        for (EventInput e : sorted) {
            EventScore s = scoreEvent(e.features());
            boolean sameBurst = previous != null && Duration.between(previous, e.at()).toMillis() <= config.burstMillis();
            if (sameBurst && current != null) {
                if (s.score() > current.score) {
                    groups.set(groups.size() - 1, new Group(current.at, s.score(), s.reason()));
                    current = groups.get(groups.size() - 1);
                }
            } else {
                current = new Group(e.at(), s.score(), s.reason());
                groups.add(current);
            }
            previous = e.at();
        }
        return groups;
    }

    private static String describe(Duration d) {
        long minutes = d.toMinutes();
        if (minutes < 120) return minutes + " minutes";
        long hours = d.toHours();
        return hours < 48 ? hours + " hours" : (hours / 24) + " days";
    }

    private static String capitalise(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
