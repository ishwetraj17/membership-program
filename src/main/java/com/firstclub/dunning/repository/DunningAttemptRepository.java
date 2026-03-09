package com.firstclub.dunning.repository;

import com.firstclub.dunning.entity.DunningAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DunningAttemptRepository extends JpaRepository<DunningAttempt, Long> {

    List<DunningAttempt> findByStatusAndScheduledAtLessThanEqual(
            DunningAttempt.DunningStatus status, LocalDateTime scheduledAt);

    List<DunningAttempt> findBySubscriptionId(Long subscriptionId);

    List<DunningAttempt> findBySubscriptionIdAndStatus(
            Long subscriptionId, DunningAttempt.DunningStatus status);

    long countBySubscriptionIdAndStatus(Long subscriptionId, DunningAttempt.DunningStatus status);

    /** Used by DunningServiceV2 to find only policy-driven v2 attempts. */
    List<DunningAttempt> findByDunningPolicyIdIsNotNullAndStatusAndScheduledAtLessThanEqual(
            DunningAttempt.DunningStatus status, LocalDateTime threshold);

    /** Count remaining v2 policy attempts for a subscription. */
    long countBySubscriptionIdAndDunningPolicyIdIsNotNullAndStatus(
            Long subscriptionId, DunningAttempt.DunningStatus status);

    /** Global count by status — used for operational health / backlog reporting. */
    long countByStatus(DunningAttempt.DunningStatus status);

    /**
     * Atomically claims a batch of due v2 dunning attempts using {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p><b>Guard:</b> BusinessLockScope.DUNNING_ATTEMPT_PROCESSING
     * <p>Using {@code SKIP LOCKED} means rows that are already locked by another in-flight
     * worker transaction are skipped rather than waited on.  This gives each scheduler pod
     * a disjoint set of attempts to process, eliminating double-charge risk under
     * multi-pod deployment.
     *
     * <p>The {@code LIMIT :limit} cap prevents a single scheduler run from claiming an
     * unbounded number of rows and holding locks for too long.
     *
     * <p><b>Note:</b> This is a native query because JPQL does not support
     * {@code FOR UPDATE SKIP LOCKED}.
     */
    @Query(value = """
            SELECT * FROM dunning_attempts
            WHERE dunning_policy_id IS NOT NULL
              AND status = 'SCHEDULED'
              AND scheduled_at <= :now
            ORDER BY scheduled_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DunningAttempt> findDueForProcessingWithSkipLocked(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);
}
