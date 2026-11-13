package dev.badalsingh.tracker.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * One tracked recipient. {@code publicId} is the random id embedded (with an HMAC) in the pixel/link
 * URLs. {@code score} and {@code lastEventAt} are a snapshot for filtering; the dashboard always
 * recomputes the exact verdict from the events.
 */
@Entity
@Table(name = "tracked_recipient", indexes = @Index(name = "idx_recipient_message", columnList = "message_id"))
public class TrackedRecipient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "public_id", nullable = false, unique = true, length = 22)
    private String publicId;

    @Column(nullable = false, length = 1000)
    private String address;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private int score = 0;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    protected TrackedRecipient() {}

    public TrackedRecipient(Long messageId, String publicId, String address) {
        this.messageId = messageId;
        this.publicId = publicId;
        this.address = address;
    }

    public Long getId() { return id; }
    public Long getMessageId() { return messageId; }
    public String getPublicId() { return publicId; }
    public String getAddress() { return address; }
    public Instant getCreatedAt() { return createdAt; }
    public int getScore() { return score; }
    public Instant getLastEventAt() { return lastEventAt; }
}
