package com.firstclub.ledger.revenue.service;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;

import java.time.LocalDate;

/**
 * Catch-up posting service for overdue revenue recognition schedules.
 *
 * <h3>When to use</h3>
 * When the nightly recognition scheduler missed a run (e.g., due to a
 * deployment outage), this service finds all {@code PENDING} rows whose
 * {@code recognition_date ≤ asOf} and posts them — subject to the
 * {@link com.firstclub.ledger.revenue.guard.RevenueRecognitionGuard} policy
 * for each row's current subscription and invoice state.
 *
 * <h3>Guard semantics during catch-up</h3>
 * <ul>
 *   <li>{@code ALLOW} / {@code FLAG}  — post the row and set {@code catch_up_run=true}</li>
 *   <li>{@code DEFER}                 — leave {@code PENDING}; update guard fields</li>
 *   <li>{@code BLOCK} / {@code HALT}  — set {@code status=SKIPPED}; update guard fields</li>
 * </ul>
 */
public interface RevenueCatchUpService {

    /**
     * Posts all overdue PENDING schedule rows through {@code asOf}.
     *
     * @param asOf upper bound (inclusive) for {@code recognition_date}; pass
     *             {@code LocalDate.now()} for a standard catch-up run
     * @return summary of the run
     */
    RevenueRecognitionRunResponseDTO runCatchUp(LocalDate asOf);
}
