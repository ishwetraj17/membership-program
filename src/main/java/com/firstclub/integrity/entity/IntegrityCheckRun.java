package com.firstclub.integrity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Persisted record of a single integrity-check run.
 *
 * <p>One row is created when {@code POST /admin/integrity/check} is called. The
 * run aggregates the results of all {@link com.firstclub.integrity.InvariantChecker}
 * instances registered in the Spring context.
 */
@Entity
@Table(
    name = "integrity_check_runs",
    indexes = {
        @Index(name = "idx_integrity_runs_started_at", columnList = "started_at DESC"),
        @Index(name = "idx_integrity_runs_status",     columnList = "status")
    }
)
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

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IntegrityCheckRunStatus status;

    /** Free-form string: admin user id, "scheduler", "ci-pipeline", etc. */
    @Column(name = "triggered_by", length = 128)
    private String triggeredBy;

    /** Inbound HTTP request id (from X-Request-Id header), if present. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** Correlation id propagated from an upstream service, if present. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;
}
