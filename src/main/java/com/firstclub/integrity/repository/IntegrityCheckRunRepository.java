package com.firstclub.integrity.repository;

import com.firstclub.integrity.entity.IntegrityCheckRun;
import com.firstclub.integrity.entity.IntegrityCheckRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface IntegrityCheckRunRepository extends JpaRepository<IntegrityCheckRun, Long> {

    /** Most recent runs first — used by the admin list endpoint. */
    List<IntegrityCheckRun> findTop20ByOrderByStartedAtDesc();

    /** Find runs that are still in RUNNING state (for stale-run detection). */
    List<IntegrityCheckRun> findByStatus(IntegrityCheckRunStatus status);

    /** Runs started after a given timestamp (for trend reports). */
    List<IntegrityCheckRun> findByStartedAtAfter(LocalDateTime since);
}
