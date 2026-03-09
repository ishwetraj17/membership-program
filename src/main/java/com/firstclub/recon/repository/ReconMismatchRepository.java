package com.firstclub.recon.repository;

import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconMismatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReconMismatchRepository extends JpaRepository<ReconMismatch, Long> {

    List<ReconMismatch> findByReportId(Long reportId);

    Page<ReconMismatch> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ReconMismatch> findByStatus(ReconMismatchStatus status, Pageable pageable);

    List<ReconMismatch> findByReportIdAndStatus(Long reportId, ReconMismatchStatus status);

    /** Platform-wide count by status — used by deep health checks. */
    long countByStatus(ReconMismatchStatus status);
}
