package com.firstclub.ledger.repository;

import com.firstclub.ledger.entity.LedgerLine;
import com.firstclub.ledger.entity.LineDirection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerLineRepository extends JpaRepository<LedgerLine, Long> {

    List<LedgerLine> findByEntryId(Long entryId);

    // ── Phase 11: integrity-check queries ────────────────────────────────────

    /** Platform-wide sum of all line amounts with the given direction. Used by
     *  {@code BalanceSheetEquationChecker} to verify total DR == total CR. */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM LedgerLine l WHERE l.direction = :direction")
    BigDecimal sumByDirection(@Param("direction") LineDirection direction);

    /** Sum of amounts for one account in one direction. Used by deferred-revenue
     *  and asset non-negative checkers to compute per-account net balances. */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM LedgerLine l WHERE l.accountId = :accountId AND l.direction = :direction")
    BigDecimal sumByAccountIdAndDirection(@Param("accountId") Long accountId,
                                          @Param("direction") LineDirection direction);

    /** Finds ledger lines whose entry_id has no corresponding ledger_entries row
     *  (foreign-key orphan check — defence-in-depth after Phase 10 immutability). */
    @Query("SELECT l FROM LedgerLine l WHERE l.entryId NOT IN (SELECT e.id FROM LedgerEntry e)")
    List<LedgerLine> findOrphans();
}
