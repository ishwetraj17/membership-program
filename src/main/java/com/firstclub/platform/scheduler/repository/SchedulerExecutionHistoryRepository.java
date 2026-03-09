package com.firstclub.platform.scheduler.repository;

import com.firstclub.platform.scheduler.entity.SchedulerExecutionHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA repository for {@link SchedulerExecutionHistory}.
 *
 * <p>All query methods use DB-side ordering on indexed columns to stay
 * efficient as row counts grow.  Callers should pass a {@code limit} or
 * use {@link org.springframework.data.domain.Pageable} for large result sets.
 */
@Repository
public interface SchedulerExecutionHistoryRepository
        extends JpaRepository<SchedulerExecutionHistory, Long> {

    /**
     * Returns the most recent execution record for the named scheduler,
     * regardless of status.
     */
    @Query("SELECT h FROM SchedulerExecutionHistory h " +
           "WHERE h.schedulerName = :name " +
           "ORDER BY h.startedAt DESC " +
           "LIMIT 1")
    Optional<SchedulerExecutionHistory> findLatestBySchedulerName(
            @Param("name") String schedulerName);

    /**
     * Returns the most recent <em>successful</em> execution for the named
     * scheduler.  Used by {@link com.firstclub.platform.scheduler.health.SchedulerHealthMonitor}
     * to detect stale schedulers.
     */
    @Query("SELECT h FROM SchedulerExecutionHistory h " +
           "WHERE h.schedulerName = :name AND h.status = 'SUCCESS' " +
           "ORDER BY h.completedAt DESC " +
           "LIMIT 1")
    Optional<SchedulerExecutionHistory> findLatestSuccessBySchedulerName(
            @Param("name") String schedulerName);

    /**
     * Returns recent execution history for a named scheduler, newest first.
     * Suitable for the {@code /ops/schedulers/history} admin endpoint.
     */
    @Query("SELECT h FROM SchedulerExecutionHistory h " +
           "WHERE h.schedulerName = :name " +
           "ORDER BY h.startedAt DESC " +
           "LIMIT :limit")
    List<SchedulerExecutionHistory> findRecentBySchedulerName(
            @Param("name") String schedulerName,
            @Param("limit") int limit);

    /**
     * Returns all execution records across all schedulers within a time window.
     * Useful for the global history view.
     */
    @Query("SELECT h FROM SchedulerExecutionHistory h " +
           "WHERE h.startedAt >= :since " +
           "ORDER BY h.startedAt DESC")
    List<SchedulerExecutionHistory> findAllSince(@Param("since") Instant since);

    /**
     * Returns all distinct scheduler names that have ever run.
     * Used to build the health summary for all known schedulers.
     */
    @Query("SELECT DISTINCT h.schedulerName FROM SchedulerExecutionHistory h")
    List<String> findAllKnownSchedulerNames();

    /**
     * Returns any RUNNING records started before {@code staleThreshold}.
     * Used to detect crashed executions that never updated to SUCCESS/FAILED.
     */
    @Query("SELECT h FROM SchedulerExecutionHistory h " +
           "WHERE h.status = 'RUNNING' AND h.startedAt < :staleThreshold")
    List<SchedulerExecutionHistory> findStaleRunningRecords(
            @Param("staleThreshold") Instant staleThreshold);
}
