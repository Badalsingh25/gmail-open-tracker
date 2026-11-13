package dev.badalsingh.tracker.repo;

import dev.badalsingh.tracker.model.TrackEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TrackEventRepository extends JpaRepository<TrackEvent, Long> {
    List<TrackEvent> findTop300ByRecipientIdOrderByOccurredAtDesc(Long recipientId);

    List<TrackEvent> findByRecipientIdInOrderByOccurredAtAsc(Collection<Long> recipientIds);

    @Modifying
    @Query("delete from TrackEvent e where e.recipientId in " +
            "(select r.id from TrackedRecipient r where r.messageId = :messageId)")
    int deleteByMessage(@Param("messageId") Long messageId);
}
