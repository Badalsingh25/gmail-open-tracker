package dev.badalsingh.tracker.model;

import dev.badalsingh.tracker.core.EventInput;
import dev.badalsingh.tracker.core.Features;
import jakarta.persistence.*;
import java.time.Instant;

/** Append-only record of one pixel fetch or link click. Holds derived features only: no IP, no User-Agent. */
@Entity
@Table(name = "track_event", indexes = @Index(name = "idx_event_recipient_time", columnList = "recipient_id, occurred_at"))
public class TrackEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(name = "link_id")
    private Long linkId;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(name = "ua_class", nullable = false, length = 24)
    private String uaClass;

    @Column(name = "ip_class", nullable = false, length = 16)
    private String ipClass;

    @Column(name = "offset_seconds", nullable = false)
    private long offsetSeconds;

    protected TrackEvent() {}

    public TrackEvent(Long recipientId, Long linkId, Instant occurredAt, Features f) {
        this.recipientId = recipientId;
        this.linkId = linkId;
        this.occurredAt = occurredAt;
        this.kind = f.kind().name();
        this.method = f.method();
        this.uaClass = f.uaClass();
        this.ipClass = f.ipClass();
        this.offsetSeconds = f.offsetSeconds();
    }

    public Long getId() { return id; }
    public Long getRecipientId() { return recipientId; }
    public Long getLinkId() { return linkId; }
    public Instant getOccurredAt() { return occurredAt; }

    public Features toFeatures() {
        return new Features(Features.Kind.valueOf(kind), method, uaClass, ipClass, offsetSeconds);
    }

    public EventInput toInput() { return new EventInput(occurredAt, toFeatures()); }
}
