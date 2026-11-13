package dev.badalsingh.tracker.model;

import jakarta.persistence.*;

/** A destination registered by the authenticated API. The redirect target is never taken from the URL. */
@Entity
@Table(name = "tracked_link", indexes = @Index(name = "idx_link_message", columnList = "message_id"))
public class TrackedLink {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(nullable = false, length = 2048)
    private String url;

    protected TrackedLink() {}

    public TrackedLink(Long messageId, String url) {
        this.messageId = messageId;
        this.url = url;
    }

    public Long getId() { return id; }
    public Long getMessageId() { return messageId; }
    public String getUrl() { return url; }
}
