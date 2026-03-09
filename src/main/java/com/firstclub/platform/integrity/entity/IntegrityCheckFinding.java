package com.firstclub.platform.integrity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persistent record of one checker's result within an
 * {@link IntegrityCheckRun}.  One finding is created per checker per run.
 */
@Entity
@Table(name = "integrity_check_findings",
       indexes = {
           @Index(name = "idx_icf_run_id",          columnList = "run_id"),
           @Index(name = "idx_icf_invariant_key",   columnList = "invariant_key"),
           @Index(name = "idx_icf_severity_status", columnList = "severity, status")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class IntegrityCheckFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "invariant_key", nullable = false, length = 128)
    private String invariantKey;

    /** CRITICAL, HIGH, MEDIUM, LOW */
    @Column(name = "severity", nullable = false, length = 32)
    private String severity;

    /** PASS, FAIL, ERROR */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "violation_count", nullable = false)
    @Builder.Default
    private int violationCount = 0;

    /**
     * JSON: {@code {"details":"...","violations":[{"entityType":"...","entityId":1,...},...]}}.
     * May be null for PASS results.
     */
    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    /** Key of the suggested repair action; null if no automated repair is known. */
    @Column(name = "suggested_repair_key", length = 128)
    private String suggestedRepairKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
