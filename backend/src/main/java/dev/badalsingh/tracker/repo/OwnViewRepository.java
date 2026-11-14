package dev.badalsingh.tracker.repo;

import dev.badalsingh.tracker.model.OwnView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OwnViewRepository extends JpaRepository<OwnView, Long> {
    List<OwnView> findByRecipientId(Long recipientId);

    List<OwnView> findByRecipientIdIn(Collection<Long> recipientIds);

    boolean existsByRecipientIdAndViewedAtAfter(Long recipientId, Instant after);

    @Modifying
    @Query("delete from OwnView v where v.recipientId in " +
            "(select r.id from TrackedRecipient r where r.messageId = :messageId)")
    int deleteByMessage(@Param("messageId") Long messageId);
}
