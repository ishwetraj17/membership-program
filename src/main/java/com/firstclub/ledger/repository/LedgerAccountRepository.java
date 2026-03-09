package com.firstclub.ledger.repository;

import com.firstclub.ledger.entity.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {
    Optional<LedgerAccount> findByName(String name);

    // ── Phase 11: integrity-check queries ────────────────────────────────────
    /** All accounts of a given type — used by AssetAccountNonNegativeChecker. */
    List<LedgerAccount> findByAccountType(LedgerAccount.AccountType accountType);
}
