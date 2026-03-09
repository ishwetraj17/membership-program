package com.firstclub.outbox.api;

import com.firstclub.outbox.lease.OutboxLeaseRecoveryService;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.platform.ops.dto.OutboxLagResponseDTO;
import com.firstclub.platform.ops.service.OutboxOpsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Ops endpoints for monitoring and manually controlling the transactional outbox.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 *
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>{@code /ops/outbox/lag}</td>
 *       <td>Returns outbox lag metrics (event counts by status, oldest pending age, etc.)</td></tr>
 *   <tr><td>POST</td><td>{@code /ops/outbox/recover-stale-leases}</td>
 *       <td>Triggers immediate stale-lease recovery for both lease-based and legacy events</td></tr>
 *   <tr><td>POST</td><td>{@code /ops/outbox/requeue/{id}}</td>
 *       <td>Resets a single event to NEW so it will be retried on the next poll cycle</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/ops/outbox")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class OutboxOpsController {

    private final OutboxOpsService           outboxOpsService;
    private final OutboxLeaseRecoveryService leaseRecoveryService;
    private final OutboxService              outboxService;

    /**
     * Returns current outbox lag metrics: event counts by status, oldest pending
     * age, count of stale leases, and per-event-type breakdown.
     */
    @GetMapping("/lag")
    public ResponseEntity<OutboxLagResponseDTO> getLag() {
        return ResponseEntity.ok(outboxOpsService.getOutboxLag());
    }

    /**
     * Triggers immediate stale-lease recovery without waiting for the next
     * scheduled run.  Returns the count of events recovered by each strategy.
     */
    @PostMapping("/recover-stale-leases")
    public ResponseEntity<Map<String, Integer>> recoverStaleLeases() {
        int leaseBased = leaseRecoveryService.recoverExpiredLeases();
        int legacy     = leaseRecoveryService.recoverLegacyStaleLeases(OutboxService.STALE_LEASE_MINUTES);
        return ResponseEntity.ok(Map.of(
                "lease_based_recovered", leaseBased,
                "legacy_recovered",      legacy,
                "total_recovered",       leaseBased + legacy
        ));
    }

    /**
     * Resets the specified outbox event to NEW with {@code next_attempt_at = now},
     * clearing any lease metadata.  Useful for manually unblocking a stuck or
     * permanently-failed event without touching the dead-letter table.
     *
     * @param id primary key of the outbox event to requeue
     */
    @PostMapping("/requeue/{id}")
    public ResponseEntity<Map<String, Object>> requeue(@PathVariable Long id) {
        outboxService.requeueEvent(id);
        return ResponseEntity.ok(Map.of("id", id, "status", "REQUEUED"));
    }
}
