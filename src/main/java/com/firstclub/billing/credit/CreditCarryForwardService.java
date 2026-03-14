package com.firstclub.billing.credit;

import com.firstclub.billing.entity.CreditNote;
import com.firstclub.billing.repository.CreditNoteRepository;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages credit notes including carry-forward overflow.
 *
 * <p><strong>Carry-forward rule:</strong> when the total available credit on a
 * user's credit notes exceeds the amount being applied to an invoice, the
 * unused portion is not silently discarded — a new carry-forward credit note
 * is created so the balance is preserved for future invoices.
 *
 * <p>This service is the single authority over credit note creation and tracks
 * the {@code available_amount_minor} field for fast balance checks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditCarryForwardService {

    private final CreditNoteRepository creditNoteRepository;

    /**
     * Creates a brand-new credit note for a user.
     *
     * @param userId          owner of the credit
     * @param customerId      optional merchant-side customer id
     * @param currency        ISO currency code
     * @param amount          credit value (positive)
     * @param reason          human-readable reason
     * @param sourceInvoiceId invoice that triggered this credit (may be null)
     * @param expiresAt       optional expiry timestamp
     * @return the persisted credit note
     */
    @Transactional
    public CreditNote createCreditNote(Long userId,
                                       Long customerId,
                                       String currency,
                                       BigDecimal amount,
                                       String reason,
                                       Long sourceInvoiceId,
                                       LocalDateTime expiresAt) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new MembershipException("Credit amount must be positive", "INVALID_CREDIT_AMOUNT",
                    HttpStatus.BAD_REQUEST);
        }

        long minorUnits = toMinorUnits(amount);
        CreditNote note = CreditNote.builder()
                .userId(userId)
                .customerId(customerId)
                .currency(currency)
                .amount(amount)
                .usedAmount(BigDecimal.ZERO)
                .availableAmountMinor(minorUnits)
                .reason(reason)
                .sourceInvoiceId(sourceInvoiceId)
                .expiresAt(expiresAt)
                .build();

        CreditNote saved = creditNoteRepository.save(note);
        log.info("Created credit note {} for user {} amount={} {}", saved.getId(), userId, amount, currency);
        return saved;
    }

    /**
     * Returns all credit notes for a user (newest-first), suitable for the
     * customer credits API response.
     */
    @Transactional(readOnly = true)
    public List<CreditNote> getCreditsForUser(Long userId) {
        return creditNoteRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Applies available credit notes (FIFO) to cover {@code amountToApply}.
     *
     * <p>If after consuming all available notes there is remaining credit beyond
     * the invoice amount, a carry-forward credit note is created for that overflow.
     * If credit is insufficient, the remaining unpaid balance is returned.
     *
     * <p><strong>Guard:</strong> each partial application is validated against the
     * note's available balance before consuming it.
     *
     * @param userId          credit-note owner
     * @param customerId      optional merchant customer id
     * @param currency        expected currency (notes are filtered to match)
     * @param amountToApply   the invoice balance to cover
     * @param sourceInvoiceId the invoice being paid (for audit)
     * @return the remaining unpaid balance after credits (zero when fully covered)
     */
    @Transactional
    public BigDecimal applyCreditsToInvoice(Long userId,
                                             Long customerId,
                                             String currency,
                                             BigDecimal amountToApply,
                                             Long sourceInvoiceId) {
        if (amountToApply.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        List<CreditNote> available = creditNoteRepository.findAvailableByUserId(userId)
                .stream()
                .filter(cn -> currency.equalsIgnoreCase(cn.getCurrency()))
                .filter(cn -> cn.getExpiresAt() == null || cn.getExpiresAt().isAfter(LocalDateTime.now()))
                .collect(Collectors.toList());

        BigDecimal remaining = amountToApply;

        for (CreditNote note : available) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal noteBalance = note.getAvailableBalance();
            BigDecimal apply = noteBalance.min(remaining);

            note.setUsedAmount(note.getUsedAmount().add(apply));
            note.setAvailableAmountMinor(toMinorUnits(note.getAvailableBalance().max(BigDecimal.ZERO)));
            creditNoteRepository.save(note);

            remaining = remaining.subtract(apply);
            log.debug("Applied {} from credit note {} to invoice {}", apply, note.getId(), sourceInvoiceId);
        }

        return remaining; // zero = fully covered; positive = still owed
    }

    /**
     * Creates a carry-forward credit note when credit overflow exists after applying
     * all available notes to an invoice.
     *
     * <p>This is called when the total available credit on a user's wallet exceeds
     * the invoice grandTotal. The difference is preserved in a new credit note.
     *
     * @param userId           credit-note owner
     * @param customerId       optional merchant customer id
     * @param currency         ISO currency code
     * @param overflowAmount   positive overflow amount to carry forward
     * @param sourceInvoiceId  invoice from which overflow originated
     * @return the new carry-forward credit note, or null if overflowAmount <= 0
     */
    @Transactional
    public CreditNote createCarryForwardIfOverflow(Long userId,
                                                    Long customerId,
                                                    String currency,
                                                    BigDecimal overflowAmount,
                                                    Long sourceInvoiceId) {
        if (overflowAmount == null || overflowAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        log.info("Creating carry-forward credit note for user {} overflow={} invoice={}",
                userId, overflowAmount, sourceInvoiceId);
        return createCreditNote(userId, customerId, currency, overflowAmount,
                "Carry-forward from invoice #" + sourceInvoiceId,
                sourceInvoiceId,
                null /* no expiry on carry-forward notes */);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Converts a BigDecimal amount to minor currency units (e.g. paise). */
    private long toMinorUnits(BigDecimal amount) {
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }
}
