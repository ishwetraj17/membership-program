package com.firstclub.integrity.repository;

import com.firstclub.integrity.entity.IntegrityCheckResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrityCheckResultRepository extends JpaRepository<IntegrityCheckResult, Long> {

    /** All checker results for a given run, ordered by creation time. */
    List<IntegrityCheckResult> findByRunIdOrderByCreatedAtAsc(Long runId);

    /** All FAIL / ERROR results for a given run — used for quick triage. */
    List<IntegrityCheckResult> findByRunIdAndStatusNot(Long runId, String status);

    /** All results for a specific invariant across all runs — trend analysis. */
    List<IntegrityCheckResult> findByInvariantNameOrderByCreatedAtDesc(String invariantName);
}
