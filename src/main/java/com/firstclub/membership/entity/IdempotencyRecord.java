package com.firstclub.membership.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Records the outcome of an idempotent write so that a client retry with the same
 * {@code Idempotency-Key} replays the original result instead of acting twice.
 *
 * The unique constraint on the key is the concurrency guard: if two requests with the
 * same key race, only one INSERT succeeds — the other rolls back and a subsequent retry
 * replays the committed result.
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    /** Fingerprint of the request payload — detects key reuse with a different request. */
    @Column(nullable = false)
    private String requestHash;

    /** Resource type the key produced (e.g. SUBSCRIPTION). */
    @Column(nullable = false)
    private String targetType;

    /** Id of the resource produced by the original request. */
    @Column(nullable = false)
    private Long targetId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyRecord that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
