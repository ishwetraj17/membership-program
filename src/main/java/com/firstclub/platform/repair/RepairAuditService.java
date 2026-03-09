package com.firstclub.platform.repair;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.platform.repair.entity.RepairActionAudit;
import com.firstclub.platform.repair.repository.RepairActionAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Persists an immutable audit record for every repair action execution.
 *
 * <p>Audit rows are written in a new REQUIRES_NEW transaction so that an
 * application rollback on the repair itself does not suppress the audit entry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepairAuditService {

    private final RepairActionAuditRepository auditRepository;
    private final ObjectMapper                objectMapper;

    /**
     * Write an audit row for the completed repair action.
     *
     * @param context  the execution context
     * @param result   the outcome from the repair action
     * @return the saved audit entity
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RepairActionAudit record(RepairAction.RepairContext context, RepairActionResult result) {
        RepairActionAudit audit = RepairActionAudit.builder()
                .repairKey(result.getRepairKey())
                .targetType(resolveTargetType(result.getRepairKey()))
                .targetId(context.targetId())
                .actorUserId(context.actorUserId())
                .beforeSnapshotJson(result.getBeforeSnapshotJson())
                .afterSnapshotJson(result.getAfterSnapshotJson())
                .reason(context.reason())
                .status(result.isSuccess() ? "EXECUTED" : "FAILED")
                .dryRun(result.isDryRun())
                .createdAt(LocalDateTime.now())
                .build();

        RepairActionAudit saved = auditRepository.save(audit);
        log.info("Repair audit written: id={} key={} target={}/{} dryRun={} status={}",
                saved.getId(), saved.getRepairKey(), saved.getTargetType(),
                saved.getTargetId(), saved.isDryRun(), saved.getStatus());
        return saved;
    }

    /**
     * Convenience helper to serialise any object to JSON for snapshot fields.
     */
    public String toJson(@Nullable Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise snapshot to JSON: {}", e.getMessage());
            return "{\"error\":\"serialisation_failed\"}";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveTargetType(String repairKey) {
        if (repairKey == null) return "UNKNOWN";
        // derive a readable target type from the key prefix,  e.g.
        // "repair.invoice.recompute_totals" → "INVOICE"
        String[] parts = repairKey.split("\\.");
        return parts.length >= 2 ? parts[1].toUpperCase() : repairKey.toUpperCase();
    }
}
