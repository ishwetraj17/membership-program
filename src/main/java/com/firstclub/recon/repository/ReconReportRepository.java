package com.firstclub.recon.repository;

import com.firstclub.recon.entity.ReconReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface ReconReportRepository extends JpaRepository<ReconReport, Long> {

    Optional<ReconReport> findByReportDate(LocalDate reportDate);

    /**
     * Acquire a PESSIMISTIC_WRITE lock on the report row for a given date.
     *
     * <p><b>Guard:</b> BusinessLockScope.RECON_REPORT_UPSERT
     * <p>Used in {@code ReconciliationService.runForDate} to serialize concurrent
     * recon runs for the same date.  Without this lock, two concurrent runs both
     * read the same (or no) report row, both compute mismatches independently, and
     * then the {@code deleteAll + saveAll} mismatch cycle interleaves — corrupting
     * the mismatch row set.
     *
     * <p>Callers should read the lock-result and, if empty, create-and-lock in a
     * single fetch-then-insert pattern within the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ReconReport r WHERE r.reportDate = :date")
    Optional<ReconReport> findByReportDateForUpdate(@Param("date") LocalDate date);
}
