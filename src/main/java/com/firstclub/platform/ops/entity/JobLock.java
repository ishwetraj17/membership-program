package com.firstclub.platform.ops.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_locks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "jobName")
public class JobLock {

    @Id
    @Column(name = "job_name", length = 128)
    private String jobName;

    /** Exclusive until this time; null = never locked or released. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** Identifier of the pod/thread holding the lock (e.g., hostname + PID). */
    @Column(name = "locked_by", length = 128)
    private String lockedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
