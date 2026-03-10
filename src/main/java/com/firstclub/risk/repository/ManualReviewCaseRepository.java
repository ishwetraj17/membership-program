package com.firstclub.risk.repository;

import com.firstclub.risk.entity.ManualReviewCase;
import com.firstclub.risk.entity.ReviewCaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ManualReviewCaseRepository extends JpaRepository<ManualReviewCase, Long> {

    Page<ManualReviewCase> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ManualReviewCase> findByStatusOrderByCreatedAtDesc(ReviewCaseStatus status, Pageable pageable);

    // Phase 18: find cases that breached their SLA and are still open/escalated
    @Query("""
            SELECT c FROM ManualReviewCase c
            WHERE c.status IN ('OPEN', 'ESCALATED')
              AND c.slaDueAt IS NOT NULL
              AND c.slaDueAt < :now
            ORDER BY c.slaDueAt ASC
            """)
    List<ManualReviewCase> findOverdueCases(@org.springframework.data.repository.query.Param("now") LocalDateTime now);
}
