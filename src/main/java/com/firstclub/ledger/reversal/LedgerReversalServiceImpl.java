package com.firstclub.ledger.reversal;

import com.firstclub.ledger.LedgerEntryFactory;
import com.firstclub.ledger.LedgerPostingPolicy;
import com.firstclub.ledger.dto.LedgerLineRequest;
import com.firstclub.ledger.entity.LedgerAccount;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.membership.exception.MembershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 10 — Reversal-only correction model implementation.
 *
 * <h3>Reversal algorithm</h3>
 * <ol>
 *   <li>Load the original {@link LedgerEntry} (404 if missing).</li>
 *   <li>Validate via {@link LedgerPostingPolicy}: reason non-blank, not already reversed,
 *       not reversing a REVERSAL.</li>
 *   <li>Load all {@link LedgerLine}s of the original entry.</li>
 *   <li>Look up account names by id (needed by {@link LedgerService#postReversalEntry}).</li>
 *   <li>Build mirrored lines with flipped directions via {@link LedgerEntryFactory}.</li>
 *   <li>Persist via {@link LedgerService#postReversalEntry} (enforces balance +
 *       {@link com.firstclub.ledger.guard.ImmutableLedgerGuard}).</li>
 * </ol>
 *
 * <h3>Duplicate reversal policy</h3>
 * Each entry can be reversed <strong>at most once</strong>.
 * {@code existsByReversalOfEntryId(originalId)} prevents a second reversal.
 * If the reversal entry itself was wrong, reverse the reversal entry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerReversalServiceImpl implements LedgerReversalService {

    private final LedgerEntryRepository    entryRepository;
    private final LedgerLineRepository     lineRepository;
    private final LedgerAccountRepository  accountRepository;
    private final LedgerPostingPolicy      postingPolicy;
    private final LedgerEntryFactory       entryFactory;
    private final LedgerService            ledgerService;

    @Override
    @Transactional
    public LedgerEntry reverse(Long originalEntryId, String reason, Long postedByUserId) {

        // 1. Load original entry
        LedgerEntry original = entryRepository.findById(originalEntryId)
                .orElseThrow(() -> new MembershipException(
                        "Ledger entry not found: " + originalEntryId,
                        "LEDGER_ENTRY_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        // 2. Validate: reason, not already reversed, not reversing a reversal
        boolean alreadyReversed = entryRepository.existsByReversalOfEntryId(originalEntryId);
        postingPolicy.validateReversal(original, reason, alreadyReversed);

        // 3. Load original lines
        List<LedgerLine> originalLines = lineRepository.findByEntryId(originalEntryId);
        if (originalLines.isEmpty()) {
            throw new MembershipException(
                    "Original ledger entry " + originalEntryId + " has no lines",
                    "LEDGER_ENTRY_HAS_NO_LINES",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 4. Build account id → name lookup (needed by LedgerService for account resolution)
        Map<Long, String> accountNameById = buildAccountNameMap(originalLines);

        // 5. Build mirrored (flipped) line requests
        List<LedgerLineRequest> flippedLines =
                entryFactory.buildReversalLines(originalLines, accountNameById);

        // 6. Persist via LedgerService — enforces balance + immutability guard
        LedgerEntry reversal = ledgerService.postReversalEntry(
                original, flippedLines, reason, postedByUserId);

        log.info("Reversal posted: entry={} reverses entry={} reason=\"{}\" postedBy={}",
                reversal.getId(), originalEntryId, reason, postedByUserId);

        return reversal;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<Long, String> buildAccountNameMap(List<LedgerLine> lines) {
        return lines.stream()
                .map(LedgerLine::getAccountId)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> accountRepository.findById(id)
                                .map(LedgerAccount::getName)
                                .orElseThrow(() -> new MembershipException(
                                        "Ledger account not found: id=" + id,
                                        "LEDGER_ACCOUNT_NOT_FOUND",
                                        HttpStatus.INTERNAL_SERVER_ERROR))));
    }
}
