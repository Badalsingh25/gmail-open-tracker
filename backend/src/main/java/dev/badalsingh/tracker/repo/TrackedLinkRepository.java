package dev.badalsingh.tracker.repo;

import dev.badalsingh.tracker.model.TrackedLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TrackedLinkRepository extends JpaRepository<TrackedLink, Long> {
    List<TrackedLink> findByMessageIdOrderByIdAsc(Long messageId);

    Optional<TrackedLink> findByIdAndMessageId(Long id, Long messageId);

    @Modifying
    @Query("delete from TrackedLink l where l.messageId = :messageId")
    int deleteByMessage(@Param("messageId") Long messageId);
}
