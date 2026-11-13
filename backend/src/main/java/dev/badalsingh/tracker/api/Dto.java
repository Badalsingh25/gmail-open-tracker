package dev.badalsingh.tracker.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response shapes of the dashboard API. */
public final class Dto {
    private Dto() {}

    public record CreateMessageRequest(
            @Size(max = 500, message = "Subject must be at most 500 characters") String subject,
            @NotBlank(message = "clientRequestId is required") @Size(max = 64, message = "clientRequestId must be at most 64 characters") String clientRequestId,
            @Pattern(regexp = "PER_RECIPIENT|SHARED", message = "mode must be PER_RECIPIENT or SHARED") String mode,
            @NotEmpty(message = "At least one recipient is required") @Size(max = 50, message = "At most 50 recipients") List<@NotBlank @Size(max = 320) String> recipients,
            @Size(max = 50, message = "At most 50 links") List<@NotBlank @Size(max = 2048) String> links) {}

    /** pixelUrl and trackedLinks are complete, ready-to-embed public URLs for this recipient only. */
    public record RecipientRef(Long id, String address, String pixelUrl, Map<String, String> trackedLinks) {}

    public record CreateMessageResponse(Long id, String subject, boolean sharedCopy, boolean created, List<RecipientRef> recipients) {}

    public record RecipientSummary(Long id, String address, String status, int score, int pixelEvents, int clickEvents,
                                   Instant firstOpenAt, Instant lastOpenAt, List<String> evidence, boolean notMeasurable) {}

    public record MessageSummary(Long id, String subject, Instant createdAt, boolean sharedCopy,
                                 String status, int score, List<RecipientSummary> recipients) {}

    public record EventView(Long id, Instant at, String kind, String uaClass, String ipClass, long secondsAfterSend,
                            int score, boolean counted, String reason, Long linkId) {}

    public record StatsResponse(long trackedMessages, long trackedRecipients, long likelyOpened, long totalEvents) {}
}
