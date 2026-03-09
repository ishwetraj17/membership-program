package com.firstclub.risk.repository;

import com.firstclub.risk.entity.ManualReviewCase;
import com.firstclub.risk.entity.ReviewCaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualReviewCaseRepository extends JpaRepository<ManualReviewCase, Long> {

    Page<ManualReviewCase> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ManualReviewCase> findByStatusOrderByCreatedAtDesc(ReviewCaseStatus status, Pageable pageable);
}
