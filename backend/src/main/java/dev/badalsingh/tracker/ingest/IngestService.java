package dev.badalsingh.tracker.ingest;

import dev.badalsingh.tracker.core.EventInput;
import dev.badalsingh.tracker.core.Features;
import dev.badalsingh.tracker.core.OpenScorer;
import dev.badalsingh.tracker.core.SignalExtractor;
import dev.badalsingh.tracker.core.TokenSigner;
import dev.badalsingh.tracker.core.Verdict;
import dev.badalsingh.tracker.model.TrackEvent;
import dev.badalsingh.tracker.model.TrackedLink;
import dev.badalsingh.tracker.model.TrackedRecipient;
import dev.badalsingh.tracker.repo.TrackEventRepository;
import dev.badalsingh.tracker.repo.TrackedLinkRepository;
import dev.badalsingh.tracker.repo.TrackedRecipientRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The write path. Deliberately small and append-only: verify the signature (no database), look up the
 * recipient, insert one event. No row locks and no counters to contend on, so it stays fast and
 * cannot lose a request under concurrency.
 */
@Service
@ConditionalOnExpression("'${tracker.mode:all}' != 'api'")
public class IngestService {
    private final TokenSigner signer;
    private final SignalExtractor extractor;
    private final OpenScorer scorer;
    private final TrackedRecipientRepository recipients;
    private final TrackedLinkRepository links;
    private final TrackEventRepository events;

    public IngestService(TokenSigner signer, SignalExtractor extractor, OpenScorer scorer,
                         TrackedRecipientRepository recipients, TrackedLinkRepository links, TrackEventRepository events) {
        this.signer = signer;
        this.extractor = extractor;
        this.scorer = scorer;
        this.recipients = recipients;
        this.links = links;
        this.events = events;
    }

    /** Forged, malformed and unknown tokens all return empty; only the signature check avoids a DB hit. */
    @Transactional(readOnly = true)
    public Optional<TrackedRecipient> authenticate(String token) {
        return signer.verify(token).flatMap(recipients::findByPublicId);
    }

    /** The destination comes from the database, never from the request, so this cannot be an open redirect. */
    @Transactional(readOnly = true)
    public Optional<String> resolveLink(TrackedRecipient recipient, long linkId) {
        return links.findByIdAndMessageId(linkId, recipient.getMessageId()).map(TrackedLink::getUrl);
    }

    @Transactional
    public void record(TrackedRecipient recipient, Features.Kind kind, Long linkId, RequestSignals signals) {
        Instant now = Instant.now();
        long offset = Duration.between(recipient.getCreatedAt(), now).getSeconds();
        Features features = extractor.extract(kind, signals.method(), signals.userAgent(), signals.clientIp(), offset);
        events.save(new TrackEvent(recipient.getId(), linkId, now, features));

        // Refresh the filter snapshot from the full history so late evidence can lift earlier events.
        List<TrackEvent> history = new ArrayList<>(events.findTop300ByRecipientIdOrderByOccurredAtDesc(recipient.getId()));
        Collections.reverse(history);
        Verdict verdict = scorer.evaluate(history.stream().map(TrackEvent::toInput).toList());
        recipients.updateSnapshot(recipient.getId(), verdict.score(), now);
    }
}
