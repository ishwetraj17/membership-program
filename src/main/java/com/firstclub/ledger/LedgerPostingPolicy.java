package com.firstclub.ledger;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.membership.exception.MembershipException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 10 — Centralised posting validation rules.
 *
 * <p>Extracted from {@code LedgerService.validateLines()} and extended with
 * reversal-specific rules.  All rules are enforced <em>before</em> any entity
 * is persisted, so the constraint is visible at the service layer rather than
 * at the database layer.
 *
 * <ul>
 *   <li>Lines list must be non-empty.</li>
 *   <li>Every line amount must be strictly positive.</li>
 *   <li>DEBIT total must equal CREDIT total (double-entry invariant).</li>
 *   <li>REVERSAL entries must have a non-blank reason text.</li>
 *   <li>REVERSAL entries must reference an existing original entry.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class LedgerPostingPolicy {

    private final MeterRegistry meterRegistry;

    private Counter unbalancedCounter;

    @PostConstruct
    public void init() {
        // Reuse the same metric that LedgerService originally incremented
        unbalancedCounter = meterRegistry.counter("ledger_unbalanced_total");
    }

    // ── Line-level invariants ─────────────────────────────────────────────────

    /**
     * Validates that {@code lines} form a balanced double-entry set.
     *
     * @throws MembershipException (500) if empty or unbalanced
     * @throws MembershipException (400) if any amount is non-positive
     */
    public void validateLines(List<LedgerLineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            unbalancedCounter.increment();
            throw new MembershipException(
                    "Ledger entry must have at least one line",
                    "LEDGER_UNBALANCED",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        BigDecimal debitSum  = BigDecimal.ZERO;
        BigDecimal creditSum = BigDecimal.ZERO;

        for (LedgerLineRequest req : lines) {
            if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new MembershipException(
                        "Line amount must be positive",
                        "LEDGER_INVALID_AMOUNT",
                        HttpStatus.BAD_REQUEST);
            }
            if (req.getDirection() == LineDirection.DEBIT) {
                debitSum  = debitSum.add(req.getAmount());
            } else {
                creditSum = creditSum.add(req.getAmount());
            }
        }

        if (debitSum.compareTo(creditSum) != 0) {
            unbalancedCounter.increment();
            throw new MembershipException(
                    "Unbalanced ledger entry: DR=" + debitSum + " CR=" + creditSum,
                    "LEDGER_UNBALANCED",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ── Reversal-specific rules ───────────────────────────────────────────────

    /**
     * Validates that a reversal can be posted against {@code original}.
     *
     * <ul>
     *   <li>Reason must be non-blank.</li>
     *   <li>An entry cannot be reversed twice (idempotent guard).</li>
     * </ul>
     *
     * @param original             the ledger entry to be corrected
     * @param reason               explanation text supplied by the caller
     * @param alreadyReversed      {@code true} if a reversal already exists for this entry
     * @throws MembershipException (422) if reason is blank
     * @throws MembershipException (409) if a reversal already exists
     */
    public void validateReversal(LedgerEntry original, String reason, boolean alreadyReversed) {
        if (!StringUtils.hasText(reason)) {
            throw new MembershipException(
                    "Reversal reason is required",
                    "REVERSAL_REASON_REQUIRED",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (original.getEntryType() == LedgerEntryType.REVERSAL) {
            throw new MembershipException(
                    "A REVERSAL entry cannot itself be reversed — reverse the original entry instead",
                    "CANNOT_REVERSE_REVERSAL",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (alreadyReversed) {
            throw new MembershipException(
                    "Entry " + original.getId() + " has already been reversed. "
                    + "To re-issue, reverse the reversal entry.",
                    "REVERSAL_ALREADY_EXISTS",
                    HttpStatus.CONFLICT);
        }
    }
}
