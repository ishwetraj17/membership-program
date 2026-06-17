package com.firstclub.membership.repository;

import com.firstclub.membership.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxEvent.Status status);

    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, Long aggregateId);

    long countByStatus(OutboxEvent.Status status);

    /**
     * Atomically claim a PENDING event (multi-node safe): only one node's conditional update
     * succeeds, so an event is never dispatched twice even without SELECT … FOR UPDATE.
     */
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'DISPATCHED', o.dispatchedAt = :now " +
           "WHERE o.id = :id AND o.status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Return a failed event to the queue for another attempt. */
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'PENDING', o.dispatchedAt = null, o.retryCount = :retryCount " +
           "WHERE o.id = :id")
    void requeue(@Param("id") Long id, @Param("retryCount") int retryCount);

    /** Move a poison event to the dead-letter state. */
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'DEAD', o.retryCount = :retryCount WHERE o.id = :id")
    void markDead(@Param("id") Long id, @Param("retryCount") int retryCount);

    /** Retention: drop already-dispatched events older than the cutoff so the table stays bounded. */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.status = 'DISPATCHED' AND o.createdAt < :cutoff")
    int deleteDispatchedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Dead-letter events, oldest first — for the admin replay endpoint. */
    java.util.List<OutboxEvent> findTop100ByStatusOrderByIdAsc(OutboxEvent.Status status);

    /** Requeue dead-letter events for another dispatch attempt. */
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'PENDING', o.retryCount = 0, o.dispatchedAt = null " +
           "WHERE o.status = 'DEAD'")
    int replayDead();
}
