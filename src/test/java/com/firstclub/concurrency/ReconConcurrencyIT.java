package com.firstclub.concurrency;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.recon.entity.ReconReport;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.ReconReportRepository;
import com.firstclub.recon.service.ReconciliationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 — Concurrency Integration Tests: Reconciliation
 *
 * <p>Proves that the pessimistic write lock
 * ({@code SELECT ... FOR UPDATE} via {@code findByReportDateForUpdate}) on
 * the {@link ReconReport} row, combined with the {@code unique} constraint
 * on {@code report_date}, prevents duplicate reconciliation reports from
 * being committed when multiple scheduler threads concurrently call
 * {@code runForDate()} for the same date.
 *
 * <p>Scenario: 10 threads simultaneously call
 * {@code reconciliationService.runForDate(date)} for the same date.
 * The unique constraint on {@code report_date} ensures at most one row
 * can exist; the pessimistic lock serialises concurrent updates to
 * an existing row — preventing inflated mismatch counts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Reconciliation Concurrency — pessimistic-lock + unique-date guard")
class ReconConcurrencyIT extends PostgresIntegrationTestBase {

    @Autowired private ReconciliationService  reconciliationService;
    @Autowired private ReconReportRepository  reconReportRepository;
    @Autowired private ReconMismatchRepository reconMismatchRepository;

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10 concurrent runForDate() calls for the same date — exactly 1 ReconReport row persisted")
    void concurrentRunForDate_exactlyOneReport() throws Exception {
        // Use a date that has no invoices or payments so the report is trivially
        // zero-sum — the only interesting thing being measured is row-uniqueness.
        LocalDate reportDate = LocalDate.of(2020, 1, 1);

        // Clean slate for this specific date (in case other tests ran for same date)
        reconReportRepository.findByReportDate(reportDate)
                .ifPresent(r -> {
                    reconMismatchRepository.deleteAll(reconMismatchRepository.findByReportId(r.getId()));
                    reconReportRepository.delete(r);
                });

        int threads = 10;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();            // all threads start simultaneously
                try {
                    reconciliationService.runForDate(reportDate);
                    return "ok";
                } catch (Exception e) {
                    // DataIntegrityViolationException (duplicate key) or
                    // TransactionSystemException (serialization) are both
                    // acceptable — they mean another thread already committed.
                    return "fail:" + e.getClass().getSimpleName();
                }
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long successCount = futures.stream().filter(f -> {
            try { return "ok".equals(f.get()); } catch (Exception ex) { return false; }
        }).count();

        // At least one thread must succeed
        assertThat(successCount)
                .as("at least one runForDate() call should succeed")
                .isGreaterThanOrEqualTo(1);

        // Core invariant: exactly one report row for this date (unique constraint)
        long reportCount = reconReportRepository.findByReportDate(reportDate)
                        .stream().count();
        assertThat(reportCount)
                .as("exactly one ReconReport must exist for the date after concurrent runs")
                .isEqualTo(1);

        // Mismatches must be consistent — no inflated duplicates from double-run
        Optional<ReconReport> report = reconReportRepository.findByReportDate(reportDate);
        assertThat(report).isPresent();
        long mismatchCount = reconMismatchRepository.findByReportId(report.get().getId()).size();
        assertThat(mismatchCount)
                .as("mismatch count must equal report.mismatchCount — no double-insertion")
                .isEqualTo(report.get().getMismatchCount());
    }

    @Test
    @DisplayName("10 concurrent idempotent re-runs on existing report — final state is consistent")
    void concurrentRerunOnExisting_idempotent() throws Exception {
        LocalDate reportDate = LocalDate.of(2020, 2, 1);

        // Ensure a clean report exists before the concurrent re-run
        reconReportRepository.findByReportDate(reportDate)
                .ifPresent(r -> {
                    reconMismatchRepository.deleteAll(reconMismatchRepository.findByReportId(r.getId()));
                    reconReportRepository.delete(r);
                });
        reconciliationService.runForDate(reportDate);

        // Confirm initial state
        assertThat(reconReportRepository.findByReportDate(reportDate)).isPresent();

        int threads = 10;
        CyclicBarrier      barrier = new CyclicBarrier(threads);
        ExecutorService    pool    = Executors.newFixedThreadPool(threads);
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    reconciliationService.runForDate(reportDate);
                } catch (Exception ignored) {
                    // Concurrent runs that lose the lock race may throw;
                    // the important thing is the final DB state is consistent.
                }
                return null;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        for (Future<Void> f : futures) {
            f.get();
        }

        // Only one report row must exist — no duplicates despite concurrent re-runs
        long reportCount = reconReportRepository.findByReportDate(reportDate)
                        .stream().count();
        assertThat(reportCount)
                .as("exactly one ReconReport must exist after concurrent idempotent re-runs")
                .isEqualTo(1);

        ReconReport finalReport = reconReportRepository.findByReportDate(reportDate).orElseThrow();
        long dbMismatches = reconMismatchRepository.findByReportId(finalReport.getId()).size();
        assertThat(dbMismatches)
                .as("DB mismatch rows must equal report.mismatchCount — no double-run artefacts")
                .isEqualTo(finalReport.getMismatchCount());
    }
}
