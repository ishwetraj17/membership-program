package com.firstclub.reporting.projections.service;

import com.firstclub.ledger.dto.LedgerAccountBalanceDTO;
import com.firstclub.ledger.service.LedgerService;
import com.firstclub.reporting.projections.dto.LedgerBalanceSnapshotDTO;
import com.firstclub.reporting.projections.entity.LedgerBalanceSnapshot;
import com.firstclub.reporting.projections.repository.LedgerBalanceSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures point-in-time ledger balances so that reporting reads can avoid
 * scanning the hot {@code ledger_lines} table.
 *
 * <h3>Platform vs merchant snapshots</h3>
 * <p>{@link #generateSnapshotForDate} creates <em>platform-level</em> snapshots
 * (merchant_id = NULL). Merchant-specific snapshots can be added later by
 * extending the caller to pass a non-null merchantId.
 *
 * <h3>Idempotency</h3>
 * <p>Re-running for the same date is safe: an existence check in code (plus a
 * partial unique index in Postgres for production) prevents duplicate rows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerSnapshotService {

    private final LedgerService                   ledgerService;
    private final LedgerBalanceSnapshotRepository snapshotRepository;

    /**
     * Compute current balances from the ledger and persist one
     * {@link LedgerBalanceSnapshot} row per account for the given date.
     *
     * <p>If a snapshot for an account + date already exists, the existing row is
     * included in the result list without creating a duplicate.
     *
     * @param date the business date to label the snapshot with (typically today)
     * @return a DTO for every ledger account, whether newly created or pre-existing
     */
    @Transactional
    public List<LedgerBalanceSnapshotDTO> generateSnapshotForDate(LocalDate date) {
        List<LedgerAccountBalanceDTO> balances = ledgerService.getBalances();
        List<LedgerBalanceSnapshotDTO> results  = new ArrayList<>(balances.size());

        for (LedgerAccountBalanceDTO b : balances) {
            Long accountId = b.getAccountId();
            if (accountId == null) {
                log.warn("LedgerAccountBalanceDTO missing accountId for account '{}' — skipping snapshot",
                        b.getAccountName());
                continue;
            }

            boolean exists = snapshotRepository
                    .existsByAccountIdAndSnapshotDateAndMerchantIdIsNull(accountId, date);

            if (exists) {
                snapshotRepository
                        .findByAccountIdAndSnapshotDateAndMerchantIdIsNull(accountId, date)
                        .map(LedgerBalanceSnapshotDTO::from)
                        .ifPresent(results::add);
                log.debug("Snapshot already exists for account={} date={} — skipping insert", accountId, date);
            } else {
                LedgerBalanceSnapshot snapshot = LedgerBalanceSnapshot.builder()
                        .accountId(accountId)
                        .snapshotDate(date)
                        .balance(b.getBalance())
                        // merchantId not set → NULL (platform snapshot)
                        .build();
                LedgerBalanceSnapshot saved = snapshotRepository.save(snapshot);
                results.add(LedgerBalanceSnapshotDTO.from(saved));
                log.debug("Snapshot created: account={} date={} balance={}", accountId, date, b.getBalance());
            }
        }

        log.info("generateSnapshotForDate({}): {} account(s) processed", date, results.size());
        return results;
    }

    /**
     * Return snapshot rows, optionally filtered by merchant and/or date range.
     * All parameters are optional — omit to retrieve all snapshots.
     *
     * @param from       inclusive start date (null = no lower bound)
     * @param to         inclusive end date   (null = no upper bound)
     * @param merchantId tenant filter        (null = all, including platform rows)
     */
    @Transactional(readOnly = true)
    public List<LedgerBalanceSnapshotDTO> getSnapshots(LocalDate from, LocalDate to, Long merchantId) {
        return snapshotRepository.findWithFilters(merchantId, from, to)
                .stream()
                .map(LedgerBalanceSnapshotDTO::from)
                .toList();
    }
}
