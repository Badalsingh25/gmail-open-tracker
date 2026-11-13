package dev.badalsingh.tracker.api;

import dev.badalsingh.tracker.api.Dto.*;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Authenticated (see ApiKeyFilter). Not mounted in ingest mode. */
@RestController
@ConditionalOnExpression("'${tracker.mode:all}' != 'ingest'")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) { this.service = service; }

    /** Register an email and get one pixel URL (and rewritten links) per recipient. Safe to retry. */
    @PostMapping("/api/messages")
    public ResponseEntity<CreateMessageResponse> create(@Valid @RequestBody CreateMessageRequest request) {
        try {
            CreateMessageResponse response = service.create(request);
            return ResponseEntity.status(response.created() ? HttpStatus.CREATED : HttpStatus.OK).body(response);
        } catch (DataIntegrityViolationException duplicate) {
            // Two simultaneous requests with the same clientRequestId: the loser re-reads the winner's record.
            return ResponseEntity.ok(service.create(request));
        }
    }

    @GetMapping("/api/messages/recent")
    public List<MessageSummary> recent() { return service.recent(); }

    @GetMapping("/api/messages/search")
    public Page<MessageSummary> search(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(defaultValue = "all") String filter) {
        return service.search(q, page, size, filter);
    }

    @GetMapping("/api/recipients/{id}/events")
    public List<EventView> events(@PathVariable Long id) { return service.eventHistory(id); }

    @DeleteMapping("/api/messages/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/stats")
    public StatsResponse stats() { return service.stats(); }
}
