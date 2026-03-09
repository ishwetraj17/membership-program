package com.firstclub.platform.scheduler.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * Durable audit record for a single scheduler execution cycle.
 *
 * <p>One row is written per scheduler invocation, regardless of whether the
 * scheduler acquired the advisory lock or was skipped.  The {@link #status}
 * column captures the outcome:
 * <ul>
 *   <li>{@code RUNNING} — lock acquired, execution in progress</li>
 *   <li>{@code SUCCESS} — completed normally; {@link #processedCount} is set</li>
 *   <li>{@code FAILED}  — exception thrown; {@link #errorMessage} is set</li>
 *   <li>{@code SKIPPED} — advisory lock busy or primary-only check failed</li>
 * </ul>
 *
 * <p>Rows are managed by {@link com.firstclub.platform.scheduler.SchedulerExecutionRecorder}.
 */
@Entity
@Table(
    name = "scheduler_execution_history",
    indexes = {
        @Index(name = "idx_sch_exec_name_started",  columnList = "scheduler_name, started_at DESC"),
        @Index(name = "idx_sch_exec_node_id",        columnList = "node_id"),
        @Index(name = "idx_sch_exec_status_created", columnList = "status, created_at DESC")
    }
)
@Data
@EqualsAndHashCode(of = "id")
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchedulerExecutionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Logical name of the scheduler, e.g. {@code "subscription-renewal"}. */
    @Column(name = "scheduler_name", nullable = false, length = 128)
    private String schedulerName;

    /**
     * Stable node identifier: {@code hostname-{8char UUID prefix}}.
     * Derived from {@link com.firstclub.platform.lock.redis.LockOwnerIdentityProvider#getInstanceId()}.
     */
    @Column(name = "node_id", nullable = false, length = 255)
    private String nodeId;

    /** DB-time timestamp when execution started (or when the row was written for SKIPPED). */
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** DB-time timestamp when execution completed.  {@code null} while {@code RUNNING}. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Execution outcome.
     * Values: {@code RUNNING}, {@code SUCCESS}, {@code FAILED}, {@code SKIPPED}.
     */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /** Number of domain objects processed (subscriptions renewed, keys expired, etc.). */
    @Column(name = "processed_count")
    private Integer processedCount;

    /**
     * First 4000 chars of the exception message when {@code status = FAILED}.
     * Truncated to avoid storing unbounded stack-trace content.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // ── Status constants ─────────────────────────────────────────────────────

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS  = "SUCCESS";
    public static final String STATUS_FAILED   = "FAILED";
    public static final String STATUS_SKIPPED  = "SKIPPED";
}
