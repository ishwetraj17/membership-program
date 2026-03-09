package com.firstclub.recon.gateway;

import com.firstclub.payments.entity.PaymentAttemptStatus;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.recon.classification.ReconExpectation;
import com.firstclub.recon.classification.ReconSeverity;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.repository.ReconMismatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Detects <em>orphaned gateway payments</em>: succeeded payment attempts that
 * carry a {@code gateway_transaction_id} but whose linked
 * {@link com.firstclub.payments.entity.PaymentIntentV2} has no {@code invoice_id}.
 *
 * <h3>Why this matters</h3>
 * The gateway has charged the customer and reported a successful transaction, but
 * the FirstClub system has not associated the payment with any invoice.  This
 * means real money was received that is not accounted for in the billing system.
 *
 * <h3>Reconciliation anchor</h3>
 * A payment attempt's {@code gateway_transaction_id} (set by the gateway on a
 * successful charge) is the primary reconciliation anchor — not the invoice alone.
 * When the intent's {@code invoice_id} is {@code null}, the gateway transaction
 * is orphaned from the billing system.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanGatewayPaymentChecker {

    private final PaymentAttemptRepository  attemptRepository;
    private final ReconMismatchRepository   mismatchRepository;

    /**
     * Finds all orphaned gateway payments and records a {@link MismatchType#ORPHAN_GATEWAY_PAYMENT}
     * mismatch for each.
     *
     * <p>A payment attempt is considered orphaned when:
     * <ul>
     *   <li>Its status is {@link PaymentAttemptStatus#SUCCEEDED}.</li>
     *   <li>Its {@code gateway_transaction_id} is non-null (gateway confirmed the charge).</li>
     *   <li>The linked {@link com.firstclub.payments.entity.PaymentIntentV2#getInvoiceId()}
     *       is {@code null} (no billing reference).</li>
     * </ul>
     *
     * @param reportId the owning {@link com.firstclub.recon.entity.ReconReport} ID
     * @return list of newly persisted mismatch records
     */
    @Transactional
    public List<ReconMismatch> check(Long reportId) {
        List<ReconMismatch> created = attemptRepository
                .findSucceededWithGatewayTxnAndNoInvoice()
                .stream()
                .map(attempt -> {
                    String details = String.format(
                            "Gateway transaction '%s' (attemptId=%d, intentId=%d) has no invoice — funds received without billing anchor",
                            attempt.getGatewayTransactionId(),
                            attempt.getId(),
                            attempt.getPaymentIntent().getId());

                    log.warn("Orphan gateway payment detected: gatewayTxnId={} attemptId={}",
                            attempt.getGatewayTransactionId(), attempt.getId());

                    return mismatchRepository.save(ReconMismatch.builder()
                            .reportId(reportId)
                            .type(MismatchType.ORPHAN_GATEWAY_PAYMENT)
                            .paymentId(attempt.getPaymentIntent().getId())
                            .gatewayTransactionId(attempt.getGatewayTransactionId())
                            .expectation(ReconExpectation.UNEXPECTED_GATEWAY_ERROR)
                            .severity(ReconSeverity.CRITICAL)
                            .details(details)
                            .build());
                })
                .toList();

        log.info("Orphan gateway payment check: reportId={} orphansFound={}", reportId, created.size());
        return created;
    }
}
