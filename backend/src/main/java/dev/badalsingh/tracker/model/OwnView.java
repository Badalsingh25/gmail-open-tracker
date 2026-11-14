package dev.badalsingh.tracker.model;

import jakarta.persistence.*;
import java.time.Instant;

/** The sender looked at their own copy (reported by the extension). Requests around it are not the recipient. */
@Entity
@Table(name = "own_view", indexes = @Index(name = "idx_ownview_recipient_time", columnList = "recipient_id, viewed_at"))
public class OwnView {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    protected OwnView() {}

    public OwnView(Long recipientId, Instant viewedAt) {
        this.recipientId = recipientId;
        this.viewedAt = viewedAt;
    }

    public Long getId() { return id; }
    public Long getRecipientId() { return recipientId; }
    public Instant getViewedAt() { return viewedAt; }
}
