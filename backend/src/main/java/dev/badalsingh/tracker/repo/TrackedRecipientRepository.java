package dev.badalsingh.tracker.repo;

import dev.badalsingh.tracker.model.TrackedRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrackedRecipientRepository extends JpaRepository<TrackedRecipient, Long> {
    Optional<TrackedRecipient> findByPublicId(String publicId);

    List<TrackedRecipient> findByMessageIdOrderByIdAsc(Long messageId);

    List<TrackedRecipient> findByMessageIdInOrderByIdAsc(Collection<Long> messageIds);

    long countByScoreGreaterThanEqual(int minScore);

    @Modifying
    @Query("update TrackedRecipient r set r.score = :score, r.lastEventAt = :at where r.id = :id")
    int updateSnapshot(@Param("id") Long id, @Param("score") int score, @Param("at") Instant at);

    @Modifying
    @Query("delete from TrackedRecipient r where r.messageId = :messageId")
    int deleteByMessage(@Param("messageId") Long messageId);
}
