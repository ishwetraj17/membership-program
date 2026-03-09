package com.firstclub.subscription.repository;

import com.firstclub.subscription.entity.SubscriptionSchedule;
import com.firstclub.subscription.entity.SubscriptionScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link SubscriptionSchedule}.
 */
@Repository
public interface SubscriptionScheduleRepository extends JpaRepository<SubscriptionSchedule, Long> {

    /** All schedules for a subscription, ordered by effective time ascending. */
    List<SubscriptionSchedule> findBySubscriptionIdOrderByEffectiveAtAsc(Long subscriptionId);

    /**
     * All SCHEDULED entries (across all subscriptions) whose effective_at is in
     * the past — used by the schedule-execution job in later phases.
     */
    List<SubscriptionSchedule> findByStatusAndEffectiveAtBefore(
            SubscriptionScheduleStatus status, LocalDateTime threshold);

    /** Tenant-scoped schedule lookup by primary key (via subscription join). */
    Optional<SubscriptionSchedule> findByIdAndSubscriptionId(Long scheduleId, Long subscriptionId);
}
