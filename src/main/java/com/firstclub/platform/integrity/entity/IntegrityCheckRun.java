package com.firstclub.platform.integrity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Persistent record of a single integrity check run.
 * One row is created per call to
 * {@link com.firstclub.platform.integrity.IntegrityRunService#runAll} or
 * {@link com.firstclub.platform.integrity.IntegrityRunService#runSingle}.
 */
@Entity
@Table(name = "integrity_check_runs",
       indexes = {
           @Index(name = "idx_icr_started_at", columnList = "started_at DESC"),
           @Index(name = "idx_icr_status",     columnList = "status"),
           @Index(name = "idx_icr_merchant",   columnList = "merchant_id")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class IntegrityCheckRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "initiated_by_user_id")
    private Long initiatedByUserId;

    /** RUNNING, COMPLETED, PARTIAL_FAILURE, ERROR */
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "RUNNING";

    @Column(name = "total_checks", nullable = false)
    @Builder.Default
    private int totalChecks = 0;

    @Column(name = "failed_checks", nullable = false)
    @Builder.Default
    private int failedChecks = 0;

    /** JSON summary of all findings: {@code [{"key":"...", "status":"PASS|FAIL"},...]} */
    @Column(name = "summary_json", columnDefinition = "TEXT")
    private String summaryJson;

    /** Optional merchant scope; null = platform-wide run. */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** Set when a single-invariant run; null when all checks were executed. */
    @Column(name = "invariant_key", length = 128)
    private String invariantKey;
}
