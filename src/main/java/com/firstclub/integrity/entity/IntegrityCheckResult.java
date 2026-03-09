package com.firstclub.integrity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persisted outcome of a single {@link com.firstclub.integrity.InvariantChecker}
 * within a specific {@link IntegrityCheckRun}.
 *
 * <p>One row per checker per run. The {@code details_json} column holds the JSON
 * serialization of all {@link com.firstclub.integrity.InvariantViolation} objects
 * found during that run.
 */
@Entity
@Table(
    name = "integrity_check_results",
    indexes = {
        @Index(name = "idx_integrity_results_run_id",         columnList = "run_id"),
        @Index(name = "idx_integrity_results_invariant_name", columnList = "invariant_name"),
        @Index(name = "idx_integrity_results_status",         columnList = "status")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class IntegrityCheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "invariant_name", nullable = false, length = 128)
    private String invariantName;

    /** PASS | FAIL | ERROR */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "violation_count", nullable = false)
    private int violationCount;

    /** CRITICAL | HIGH | MEDIUM | LOW */
    @Column(nullable = false, length = 16)
    private String severity;

    /** JSON array of {@link com.firstclub.integrity.InvariantViolation} objects. */
    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "suggested_repair_action", columnDefinition = "TEXT")
    private String suggestedRepairAction;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
