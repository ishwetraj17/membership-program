package com.firstclub.platform.repair.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Immutable audit record for a repair action execution.
 * Rows are never updated after insert.
 */
@Entity
@Table(name = "repair_actions_audit",
        indexes = {
                @Index(name = "idx_repair_audit_repair_key", columnList = "repair_key"),
                @Index(name = "idx_repair_audit_target", columnList = "target_type, target_id"),
                @Index(name = "idx_repair_audit_actor", columnList = "actor_user_id"),
                @Index(name = "idx_repair_audit_created_at", columnList = "created_at")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepairActionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repair_key", nullable = false, length = 120)
    private String repairKey;

    @Column(name = "target_type", nullable = false, length = 80)
    private String targetType;

    /** String representation of the target PK or compound key. */
    @Column(name = "target_id", length = 255)
    private String targetId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Lob
    @Column(name = "before_snapshot_json", columnDefinition = "TEXT")
    private String beforeSnapshotJson;

    @Lob
    @Column(name = "after_snapshot_json", columnDefinition = "TEXT")
    private String afterSnapshotJson;

    @Column(name = "reason", length = 500)
    private String reason;

    /** {@code EXECUTED} or {@code FAILED}. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
