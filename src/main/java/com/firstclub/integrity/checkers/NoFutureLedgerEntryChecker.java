package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Verifies that no ledger entry carries a {@code createdAt} timestamp that is
 * later than the current system clock.
 *
 * <p>Future-dated entries can indicate a clock-skew bug, a timezone mismatch,
 * or deliberate backdating/forward-dating of financial records — all of which
 * invalidate point-in-time balance calculations.
 */
@Component
@RequiredArgsConstructor
public class NoFutureLedgerEntryChecker implements InvariantChecker {

    public static final String NAME = "NO_FUTURE_LEDGER_ENTRY";
    private static final String REPAIR =
            "Investigate the server clock or timezone configuration that produced the future-dated entry. "
            + "Do NOT delete the entry (ledger is immutable). Instead, post a reversal entry and "
            + "re-post with the correct timestamp.";

    private final LedgerEntryRepository ledgerEntryRepository;

    @Override public String getName()            { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.HIGH; }

    @Override
    public InvariantResult check() {
        LocalDateTime now = LocalDateTime.now();
        List<LedgerEntry> futureEntries = ledgerEntryRepository.findByCreatedAtAfter(now);

        if (futureEntries.isEmpty()) {
            return InvariantResult.pass(NAME, getSeverity());
        }

        List<InvariantViolation> violations = futureEntries.stream()
                .map(e -> InvariantViolation.builder()
                        .entityType("LedgerEntry")
                        .entityId(String.valueOf(e.getId()))
                        .description(String.format(
                                "LedgerEntry %d (type=%s) has createdAt=%s which is after now=%s",
                                e.getId(), e.getEntryType(), e.getCreatedAt(), now))
                        .suggestedRepairAction(REPAIR)
                        .build())
                .toList();
        return InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
