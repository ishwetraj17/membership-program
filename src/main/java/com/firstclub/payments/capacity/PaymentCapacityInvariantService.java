package com.firstclub.payments.capacity;

import com.firstclub.payments.entity.Payment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Owns the full {@link Payment} capacity invariant:
 *
 * <pre>capturedAmount ≥ refundedAmount + disputedAmount  (netAmount ≥ 0)</pre>
 *
 * <p>Also responsible for keeping the parallel minor-unit BIGINT columns
 * ({@code captured_amount_minor}, {@code refunded_amount_minor},
 * {@code disputed_amount_minor}) in sync with the NUMERIC aggregate amounts
 * so that the DB-level CHECK constraints ({@code chk_payment_capacity} and
 * {@code chk_payment_amounts_non_negative}) remain satisfiable.
 *
 * <h3>Minor-unit convention</h3>
 * Since {@code Payment} amounts use {@code precision=18, scale=4}, one minor
 * unit represents 0.0001 of the base currency unit:
 * <pre>amount_minor = round(amount × 10_000)</pre>
 */
@Service
public class PaymentCapacityInvariantService {

    private static final BigDecimal MINOR_UNIT_FACTOR = BigDecimal.valueOf(10_000L);

    /**
     * Validates the capacity invariant on an in-memory {@link Payment} without
     * touching the database.  Throws {@link IllegalStateException} if violated —
     * this indicates a programming error in the calling service.
     *
     * @param payment {@link Payment} to validate (amounts must be current)
     * @throws IllegalStateException if {@code capturedAmount < refundedAmount + disputedAmount}
     */
    public void assertInvariant(Payment payment) {
        BigDecimal net = payment.getCapturedAmount()
                .subtract(payment.getRefundedAmount())
                .subtract(payment.getDisputedAmount());
        if (net.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException(
                    "[BUG] Payment " + payment.getId()
                    + " capacity invariant violated: captured=" + payment.getCapturedAmount()
                    + " refunded=" + payment.getRefundedAmount()
                    + " disputed=" + payment.getDisputedAmount()
                    + " → net=" + net);
        }
    }

    /**
     * Syncs the three minor-unit BIGINT columns from the current BigDecimal amounts.
     * Must be called immediately before every {@code paymentRepository.save(payment)}
     * that modifies any of {@code capturedAmount}, {@code refundedAmount}, or
     * {@code disputedAmount}.
     *
     * @param payment {@link Payment} to update in-place (not persisted here)
     */
    public void syncMinorUnitFields(Payment payment) {
        payment.setCapturedAmountMinor(toMinorUnits(payment.getCapturedAmount()));
        payment.setRefundedAmountMinor(toMinorUnits(payment.getRefundedAmount()));
        payment.setDisputedAmountMinor(toMinorUnits(payment.getDisputedAmount()));
    }

    private long toMinorUnits(BigDecimal amount) {
        if (amount == null) return 0L;
        return amount.multiply(MINOR_UNIT_FACTOR)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
