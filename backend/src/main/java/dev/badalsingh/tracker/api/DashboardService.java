package dev.badalsingh.tracker.api;

import dev.badalsingh.tracker.api.Dto.*;
import dev.badalsingh.tracker.core.EventInput;
import dev.badalsingh.tracker.core.EventScore;
import dev.badalsingh.tracker.core.OpenScorer;
import dev.badalsingh.tracker.core.OwnViewFilter;
import dev.badalsingh.tracker.core.Status;
import dev.badalsingh.tracker.core.TokenSigner;
import dev.badalsingh.tracker.core.Verdict;
import dev.badalsingh.tracker.model.OwnView;
import dev.badalsingh.tracker.model.TrackEvent;
import dev.badalsingh.tracker.model.TrackedLink;
import dev.badalsingh.tracker.model.TrackedMessage;
import dev.badalsingh.tracker.model.TrackedRecipient;
import dev.badalsingh.tracker.repo.OwnViewRepository;
import dev.badalsingh.tracker.repo.TrackEventRepository;
import dev.badalsingh.tracker.repo.TrackedLinkRepository;
import dev.badalsingh.tracker.repo.TrackedMessageRepository;
import dev.badalsingh.tracker.repo.TrackedRecipientRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** The read/management side. Verdicts are always recomputed from raw events, so tuning the scorer is retroactive. */
@Service
@ConditionalOnExpression("'${tracker.mode:all}' != 'ingest'")
public class DashboardService {
    private static final List<String> FILTERS = List.of("all", "opened", "activity", "none");

    private final TrackedMessageRepository messages;
    private final TrackedRecipientRepository recipients;
    private final TrackedLinkRepository links;
    private final TrackEventRepository events;
    private final OwnViewRepository ownViews;
    private final TokenSigner signer;
    private final OpenScorer scorer;
    private final String baseUrl;

    public DashboardService(TrackedMessageRepository messages, TrackedRecipientRepository recipients,
                            TrackedLinkRepository links, TrackEventRepository events,
                            OwnViewRepository ownViews, TokenSigner signer, OpenScorer scorer,
                            @Value("${tracker.public-base-url}") String publicBaseUrl) {
        this.messages = messages;
        this.recipients = recipients;
        this.links = links;
        this.events = events;
        this.ownViews = ownViews;
        this.signer = signer;
        this.scorer = scorer;
        this.baseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    @Transactional
    public CreateMessageResponse create(CreateMessageRequest request) {
        String requestId = request.clientRequestId().trim();
        Optional<TrackedMessage> existing = messages.findByClientRequestId(requestId);
        if (existing.isPresent()) return toCreateResponse(existing.get(), false); // idempotent retry

        List<String> addresses = normaliseRecipients(request.recipients());
        List<String> urls = normaliseLinks(request.links());
        boolean shared = "SHARED".equals(request.mode()) && addresses.size() > 1;

        TrackedMessage message = messages.save(new TrackedMessage(requestId, request.subject() == null ? "" : request.subject().trim(), shared));
        if (shared) {
            String label = String.join(", ", addresses);
            if (label.length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient list is too long");
            recipients.save(new TrackedRecipient(message.getId(), signer.newId(), label));
        } else {
            for (String address : addresses) recipients.save(new TrackedRecipient(message.getId(), signer.newId(), address));
        }
        for (String url : urls) links.save(new TrackedLink(message.getId(), url));
        return toCreateResponse(message, true);
    }

    @Transactional(readOnly = true)
    public List<MessageSummary> recent() {
        return summarise(messages.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 200)));
    }

    @Transactional(readOnly = true)
    public Page<MessageSummary> search(String query, int page, int size, String filter) {
        String f = filter == null ? "all" : filter.trim().toLowerCase(Locale.ROOT);
        if (!FILTERS.contains(f)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filter must be all, opened, activity or none");
        Page<TrackedMessage> result = messages.search(query == null ? "" : query.trim(), f,
                scorer.config().openedThreshold(),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)), Sort.by("createdAt").descending()));
        List<MessageSummary> summaries = summarise(result.getContent());
        return new org.springframework.data.domain.PageImpl<>(summaries, result.getPageable(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<EventView> eventHistory(Long recipientId) {
        if (!recipients.existsById(recipientId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recipient was not found");
        List<Instant> own = ownViews.findByRecipientId(recipientId).stream().map(OwnView::getViewedAt).toList();
        return events.findTop300ByRecipientIdOrderByOccurredAtDesc(recipientId).stream().map(e -> {
            if (OwnViewFilter.isOwn(e.getOccurredAt(), own)) {
                return new EventView(e.getId(), e.getOccurredAt(), e.toFeatures().kind().name(), e.toFeatures().uaClass(),
                        e.toFeatures().ipClass(), e.toFeatures().offsetSeconds(), 0, false,
                        "Your own view of the sent email (reported by the extension)", e.getLinkId());
            }
            EventScore s = scorer.scoreEvent(e.toFeatures());
            return new EventView(e.getId(), e.getOccurredAt(), e.toFeatures().kind().name(), e.toFeatures().uaClass(),
                    e.toFeatures().ipClass(), e.toFeatures().offsetSeconds(), s.score(),
                    s.score() >= scorer.config().possibleThreshold(), s.reason(), e.getLinkId());
        }).toList();
    }

    /**
     * The extension saw the sender's own Gmail load this recipient's pixel. Remember it, then refresh the
     * stored snapshot so counts and filters stop treating that fetch as the recipient's open.
     */
    @Transactional
    public void recordOwnView(String token) {
        Optional<TrackedRecipient> recipient = signer.verify(token == null ? "" : token.trim()).flatMap(recipients::findByPublicId);
        if (recipient.isEmpty()) return;
        Long id = recipient.get().getId();
        Instant now = Instant.now();
        // Gmail re-renders a message many times while it is open; keep one record per few seconds.
        if (ownViews.existsByRecipientIdAndViewedAtAfter(id, now.minusSeconds(5))) return;
        ownViews.save(new OwnView(id, now));

        List<TrackEvent> history = events.findByRecipientIdInOrderByOccurredAtAsc(List.of(id));
        if (history.isEmpty()) return;
        List<Instant> own = ownViews.findByRecipientId(id).stream().map(OwnView::getViewedAt).toList();
        Verdict verdict = scorer.evaluate(OwnViewFilter.withoutOwn(history.stream().map(TrackEvent::toInput).toList(), own));
        recipients.updateSnapshot(id, verdict.score(), history.get(history.size() - 1).getOccurredAt());
    }

    @Transactional
    public void delete(Long messageId) {
        if (!messages.existsById(messageId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked message was not found");
        events.deleteByMessage(messageId);
        ownViews.deleteByMessage(messageId);
        recipients.deleteByMessage(messageId);
        links.deleteByMessage(messageId);
        messages.deleteById(messageId);
    }

    @Transactional(readOnly = true)
    public StatsResponse stats() {
        return new StatsResponse(messages.count(), recipients.count(),
                recipients.countByScoreGreaterThanEqual(scorer.config().openedThreshold()), events.count());
    }

    // ---- helpers ----

    private CreateMessageResponse toCreateResponse(TrackedMessage message, boolean created) {
        List<TrackedLink> messageLinks = links.findByMessageIdOrderByIdAsc(message.getId());
        List<RecipientRef> refs = recipients.findByMessageIdOrderByIdAsc(message.getId()).stream().map(r -> {
            String token = signer.sign(r.getPublicId());
            Map<String, String> tracked = new LinkedHashMap<>();
            for (TrackedLink link : messageLinks) tracked.put(link.getUrl(), baseUrl + "/l/" + token + "/" + link.getId());
            return new RecipientRef(r.getId(), r.getAddress(), baseUrl + "/t/" + token + ".gif", tracked);
        }).toList();
        return new CreateMessageResponse(message.getId(), message.getSubject(), message.isSharedCopy(), created, refs);
    }

    private List<MessageSummary> summarise(List<TrackedMessage> page) {
        if (page.isEmpty()) return List.of();
        List<Long> messageIds = page.stream().map(TrackedMessage::getId).toList();
        Map<Long, List<TrackedRecipient>> byMessage = recipients.findByMessageIdInOrderByIdAsc(messageIds).stream()
                .collect(Collectors.groupingBy(TrackedRecipient::getMessageId, LinkedHashMap::new, Collectors.toList()));
        List<Long> recipientIds = byMessage.values().stream().flatMap(List::stream).map(TrackedRecipient::getId).toList();
        Map<Long, List<EventInput>> eventsByRecipient = recipientIds.isEmpty() ? Map.of()
                : events.findByRecipientIdInOrderByOccurredAtAsc(recipientIds).stream()
                .collect(Collectors.groupingBy(TrackEvent::getRecipientId, Collectors.mapping(TrackEvent::toInput, Collectors.toList())));

        Map<Long, List<Instant>> ownByRecipient = recipientIds.isEmpty() ? Map.of()
                : ownViews.findByRecipientIdIn(recipientIds).stream()
                .collect(Collectors.groupingBy(OwnView::getRecipientId, Collectors.mapping(OwnView::getViewedAt, Collectors.toList())));

        List<MessageSummary> out = new ArrayList<>();
        for (TrackedMessage m : page) {
            List<RecipientSummary> rs = byMessage.getOrDefault(m.getId(), List.of()).stream().map(r -> {
                Verdict v = scorer.evaluate(OwnViewFilter.withoutOwn(
                        eventsByRecipient.getOrDefault(r.getId(), List.of()), ownByRecipient.get(r.getId())));
                return new RecipientSummary(r.getId(), r.getAddress(), v.status(), v.score(), v.pixelEvents(), v.clickEvents(),
                        v.firstOpenAt(), v.lastOpenAt(), v.evidence(), v.notMeasurable());
            }).toList();
            RecipientSummary best = rs.stream()
                    .max(Comparator.comparingInt((RecipientSummary r) -> Status.rank(r.status())).thenComparingInt(RecipientSummary::score))
                    .orElse(null);
            out.add(new MessageSummary(m.getId(), m.getSubject(), m.getCreatedAt(), m.isSharedCopy(),
                    best == null ? Status.NO_ACTIVITY : best.status(), best == null ? 0 : best.score(), rs));
        }
        return out;
    }

    private static List<String> normaliseRecipients(List<String> raw) {
        Map<String, String> unique = new LinkedHashMap<>(); // case-insensitive de-dupe, keep first spelling
        for (String r : raw) {
            String address = r.trim();
            if (!address.contains("@") || address.length() < 3) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a valid recipient address: " + address);
            }
            unique.putIfAbsent(address.toLowerCase(Locale.ROOT), address);
        }
        return List.copyOf(unique.values());
    }

    private static List<String> normaliseLinks(List<String> raw) {
        if (raw == null) return List.of();
        Set<String> unique = new LinkedHashSet<>();
        for (String r : raw) {
            String url = r.trim();
            try {
                URI uri = new URI(url);
                boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
                if (!web || uri.getHost() == null || url.chars().anyMatch(c -> c <= 32 || c == 127)) throw new IllegalArgumentException();
            } catch (Exception e) {
                // Only plain http(s) destinations may be stored: the tracker will redirect to them.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a valid http(s) link: " + (url.length() > 80 ? url.substring(0, 80) + "…" : url));
            }
            unique.add(url);
        }
        return List.copyOf(unique);
    }
}
