package dev.badalsingh.tracker.model;

import jakarta.persistence.*;
import java.time.Instant;

/** One composed email. Each recipient (or one shared group) gets its own {@link TrackedRecipient}. */
@Entity
@Table(name = "tracked_message", indexes = @Index(name = "idx_message_created", columnList = "created_at"))
public class TrackedMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_request_id", nullable = false, unique = true, length = 64)
    private String clientRequestId;

    @Column(length = 500)
    private String subject;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** True when several people received one shared copy, so opens cannot be attributed to one of them. */
    @Column(name = "shared_copy", nullable = false)
    private boolean sharedCopy;

    protected TrackedMessage() {}

    public TrackedMessage(String clientRequestId, String subject, boolean sharedCopy) {
        this.clientRequestId = clientRequestId;
        this.subject = subject == null ? "" : subject;
        this.sharedCopy = sharedCopy;
    }

    public Long getId() { return id; }
    public String getClientRequestId() { return clientRequestId; }
    public String getSubject() { return subject; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isSharedCopy() { return sharedCopy; }
}
