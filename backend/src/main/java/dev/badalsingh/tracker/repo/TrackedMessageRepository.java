package dev.badalsingh.tracker.repo;

import dev.badalsingh.tracker.model.TrackedMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TrackedMessageRepository extends JpaRepository<TrackedMessage, Long> {
    Optional<TrackedMessage> findByClientRequestId(String clientRequestId);

    List<TrackedMessage> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** filter: all | opened (a recipient scored >= openedMin) | activity (any request) | none. */
    @Query("select m from TrackedMessage m where " +
            "(:term = '' or lower(coalesce(m.subject, '')) like lower(concat('%', :term, '%')) " +
            "  or exists (select 1 from TrackedRecipient r where r.messageId = m.id " +
            "             and lower(r.address) like lower(concat('%', :term, '%')))) and " +
            "(:filter = 'all' " +
            "  or (:filter = 'opened' and exists (select 1 from TrackedRecipient r where r.messageId = m.id and r.score >= :openedMin)) " +
            "  or (:filter = 'activity' and exists (select 1 from TrackedRecipient r where r.messageId = m.id and r.lastEventAt is not null)) " +
            "  or (:filter = 'none' and not exists (select 1 from TrackedRecipient r where r.messageId = m.id and r.lastEventAt is not null)))")
    Page<TrackedMessage> search(@Param("term") String term, @Param("filter") String filter,
                                @Param("openedMin") int openedMin, Pageable pageable);
}
