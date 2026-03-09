package com.firstclub.ledger.revenue.service;

import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;

import java.util.List;

public interface RevenueRecognitionScheduleService {

    /**
     * Generates daily revenue recognition schedule rows for a paid recurring invoice.
     *
     * <p>Rules:
     * <ul>
     *   <li>The invoice must have a non-null {@code periodStart} and {@code periodEnd}.</li>
     *   <li>The invoice must have a positive {@code grandTotal}.</li>
     *   <li>The invoice must reference a subscription (non-null {@code subscriptionId}).</li>
     *   <li>Idempotent — if schedules already exist for this invoice the existing rows
     *       are returned without creating duplicates.</li>
     *   <li>Revenue is spread evenly across each calendar day in [periodStart, periodEnd).
     *       The final day absorbs any rounding residue so total == invoice amount.</li>
     *   <li>A SHA-256 {@code generationFingerprint} is stored on every generated row,
     *       encoding the invoice parameters used at generation time (Phase 14).</li>
     * </ul>
     *
     * @param invoiceId ID of the paid invoice
     * @return the list of created (or existing if idempotent) schedule DTOs
     */
    List<RevenueRecognitionScheduleResponseDTO> generateScheduleForInvoice(Long invoiceId);

    /**
     * Force-regenerates the revenue recognition schedule for a paid invoice.
     *
     * <p>Unlike {@link #generateScheduleForInvoice}, this method:
     * <ul>
     *   <li>Deletes all existing {@code PENDING} schedule rows for the invoice
     *       (POSTED rows are never touched — they are financial source of truth).</li>
     *   <li>Generates a fresh set of daily rows from the current invoice state.</li>
     *   <li>Marks every new row with {@code catchUpRun = true} so that reports
     *       can distinguish normal recognition from catch-up regeneration.</li>
     * </ul>
     *
     * <p>Use this via the repair endpoint ({@code POST /api/v2/admin/repair/revenue-recognition/{id}/regenerate?force=true})
     * when the original schedule generation failed or the period details were corrected
     * after the fact.
     *
     * @param invoiceId ID of the paid invoice to regenerate
     * @return the newly generated schedule DTOs
     */
    List<RevenueRecognitionScheduleResponseDTO> regenerateScheduleForInvoice(Long invoiceId);

    /** List all schedules (admin view). */
    List<RevenueRecognitionScheduleResponseDTO> listAllSchedules();

    /** List all schedules for a specific invoice (detailed admin view, Phase 14). */
    List<RevenueRecognitionScheduleResponseDTO> listSchedulesByInvoice(Long invoiceId);
}
