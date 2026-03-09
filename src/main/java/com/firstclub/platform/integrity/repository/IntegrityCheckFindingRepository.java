package com.firstclub.platform.integrity.repository;

import com.firstclub.platform.integrity.entity.IntegrityCheckFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IntegrityCheckFindingRepository extends JpaRepository<IntegrityCheckFinding, Long> {

    List<IntegrityCheckFinding> findByRunId(Long runId);

    List<IntegrityCheckFinding> findByRunIdAndStatus(Long runId, String status);

    List<IntegrityCheckFinding> findByInvariantKeyOrderByCreatedAtDesc(String invariantKey);

    long countByStatus(String status);
}
