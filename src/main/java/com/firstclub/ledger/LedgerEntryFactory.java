package com.firstclub.ledger;

import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.entity.LineDirection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 10 — Factory for assembling ledger posting descriptors.
 *
 * <p>Provides lightweight builder helpers for common posting patterns:
 * <ul>
 *   <li>{@link #line} — builds a single {@link LedgerLineRequest}</li>
 *   <li>{@link #buildReversalLines} — mirrors an existing line list with flipped directions</li>
 * </ul>
 *
 * <p>This class contains no IO.  All persistence is delegated to
 * {@link com.firstclub.ledger.service.LedgerService}.
 */
@Component
public class LedgerEntryFactory {

    // ── Line builder ──────────────────────────────────────────────────────────

    /**
     * Builds a {@link LedgerLineRequest} tuple.
     *
     * @param accountName unique name of the target ledger account (e.g. {@code "PG_CLEARING"})
     * @param direction   DEBIT or CREDIT
     * @param amount      strictly positive amount
     */
    public LedgerLineRequest line(String accountName, LineDirection direction,
                                  java.math.BigDecimal amount) {
        return LedgerLineRequest.builder()
                .accountName(accountName)
                .direction(direction)
                .amount(amount)
                .build();
    }

    /** Convenience: DEBIT line. */
    public LedgerLineRequest debit(String accountName, java.math.BigDecimal amount) {
        return line(accountName, LineDirection.DEBIT, amount);
    }

    /** Convenience: CREDIT line. */
    public LedgerLineRequest credit(String accountName, java.math.BigDecimal amount) {
        return line(accountName, LineDirection.CREDIT, amount);
    }

    // ── Reversal helpers ──────────────────────────────────────────────────────

    /**
     * Mirrors {@code originalLines} with all directions flipped.
     *
     * <p>The returned list is ready to be passed to
     * {@link com.firstclub.ledger.service.LedgerService#postReversalEntry} after
     * substituting account names from an account-id→name lookup map.
     *
     * @param originalLines  loaded {@link LedgerLine} rows of the entry being reversed
     * @param accountNameById map of account_id → account_name for name resolution
     * @return balanced list of line requests with flipped debit/credit sides
     */
    public List<LedgerLineRequest> buildReversalLines(
            List<LedgerLine> originalLines,
            java.util.Map<Long, String> accountNameById) {

        return originalLines.stream()
                .map(line -> LedgerLineRequest.builder()
                        .accountName(accountNameById.get(line.getAccountId()))
                        .direction(flip(line.getDirection()))
                        .amount(line.getAmount())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Builds a skeleton {@link LedgerEntry} (without an id) for a reversal.
     * The caller must persist it via {@code LedgerService}.
     */
    public LedgerEntry buildReversalEntry(LedgerEntry original,
                                          Long reversalOfEntryId,
                                          String reversalReason,
                                          Long postedByUserId) {
        return LedgerEntry.builder()
                .entryType(LedgerEntryType.REVERSAL)
                .referenceType(original.getReferenceType())
                .referenceId(original.getReferenceId())
                .currency(original.getCurrency())
                .reversalOfEntryId(reversalOfEntryId)
                .reversalReason(reversalReason)
                .postedByUserId(postedByUserId)
                .build();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    static LineDirection flip(LineDirection d) {
        return d == LineDirection.DEBIT ? LineDirection.CREDIT : LineDirection.DEBIT;
    }
}
