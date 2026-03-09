package com.firstclub.recon.classification;

import com.firstclub.recon.entity.MismatchType;
import org.springframework.stereotype.Component;

/**
 * Classifies a reconciliation mismatch into an {@link ReconExpectation} category
 * and a {@link ReconSeverity} level given the mismatch type and contextual hints.
 *
 * <p>The classifier is <em>stateless</em> and safe to call from multiple threads.
 *
 * <h3>Rules (applied in order)</h3>
 * <ol>
 *   <li>If {@code nearDayBoundary=true} and the type is a timing-sensitive type
 *       ({@code INVOICE_NO_PAYMENT}, {@code PAYMENT_NO_INVOICE}) →
 *       {@code EXPECTED_TIMING_DIFFERENCE / WARNING}.</li>
 *   <li>Orphaned gateway payments → {@code UNEXPECTED_GATEWAY_ERROR / CRITICAL}.</li>
 *   <li>Duplicate settlement batches → {@code UNEXPECTED_SYSTEM_ERROR / CRITICAL}.</li>
 *   <li>Amount mismatches, ledger/batch variances → {@code UNEXPECTED_SYSTEM_ERROR / CRITICAL}.</li>
 *   <li>Duplicate gateway transaction IDs → {@code UNEXPECTED_GATEWAY_ERROR / CRITICAL}.</li>
 *   <li>Batch vs external-statement variances → {@code UNEXPECTED_GATEWAY_ERROR / WARNING}.</li>
 *   <li>All remaining types → {@code UNEXPECTED_SYSTEM_ERROR / WARNING}.</li>
 * </ol>
 */
@Component
public class ReconExpectationClassifier {

    /**
     * Classify a mismatch type and produce an expectation + severity pair.
     *
     * @param type            the {@link MismatchType} of the mismatch
     * @param nearDayBoundary {@code true} when the mismatch event timestamp falls
     *                        within the configured {@link com.firstclub.recon.ReconWindowPolicy}
     *                        timing margin (but outside the strict day range)
     * @return an immutable {@link ClassificationResult}
     */
    public ClassificationResult classify(MismatchType type, boolean nearDayBoundary) {
        // Rule 1: timing boundary – only relevant for missing-match types
        if (nearDayBoundary && isTimingSensitive(type)) {
            return ClassificationResult.of(
                    ReconExpectation.EXPECTED_TIMING_DIFFERENCE,
                    ReconSeverity.WARNING);
        }

        return switch (type) {
            // ── Revenue-critical defects ─────────────────────────────────────
            case ORPHAN_GATEWAY_PAYMENT  ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_GATEWAY_ERROR,  ReconSeverity.CRITICAL);
            case DUPLICATE_SETTLEMENT    ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_SYSTEM_ERROR,   ReconSeverity.CRITICAL);
            case AMOUNT_MISMATCH         ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_SYSTEM_ERROR,   ReconSeverity.CRITICAL);
            case DUPLICATE_GATEWAY_TXN   ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_GATEWAY_ERROR,  ReconSeverity.CRITICAL);
            case PAYMENT_LEDGER_VARIANCE ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_SYSTEM_ERROR,   ReconSeverity.CRITICAL);
            case LEDGER_BATCH_VARIANCE   ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_SYSTEM_ERROR,   ReconSeverity.CRITICAL);

            // ── Gateway-side warnings ─────────────────────────────────────────
            case BATCH_STATEMENT_VARIANCE ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_GATEWAY_ERROR,  ReconSeverity.WARNING);
            case PAYMENT_NO_INVOICE       ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_GATEWAY_ERROR,  ReconSeverity.WARNING);

            // ── Default ───────────────────────────────────────────────────────
            default ->
                    ClassificationResult.of(ReconExpectation.UNEXPECTED_SYSTEM_ERROR,   ReconSeverity.WARNING);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isTimingSensitive(MismatchType type) {
        return type == MismatchType.INVOICE_NO_PAYMENT
            || type == MismatchType.PAYMENT_NO_INVOICE;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    /**
     * Immutable result of a classification: expectation + severity.
     */
    public record ClassificationResult(ReconExpectation expectation, ReconSeverity severity) {

        public static ClassificationResult of(ReconExpectation exp, ReconSeverity sev) {
            return new ClassificationResult(exp, sev);
        }
    }
}
