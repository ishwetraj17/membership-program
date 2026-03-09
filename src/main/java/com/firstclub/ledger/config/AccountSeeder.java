package com.firstclub.ledger.config;

import com.firstclub.ledger.entity.LedgerAccount;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the four core double-entry accounts on application startup.
 * Runs only in the {@code dev} profile and is idempotent.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class AccountSeeder implements ApplicationRunner {

    private final LedgerAccountRepository accountRepository;

    private static final List<SeedEntry> CORE_ACCOUNTS = List.of(
            new SeedEntry("PG_CLEARING",             LedgerAccount.AccountType.ASSET),
            new SeedEntry("BANK",                    LedgerAccount.AccountType.ASSET),
            new SeedEntry("SUBSCRIPTION_LIABILITY",  LedgerAccount.AccountType.LIABILITY),
            new SeedEntry("REVENUE_SUBSCRIPTIONS",   LedgerAccount.AccountType.INCOME),
            new SeedEntry("DISPUTE_RESERVE",         LedgerAccount.AccountType.ASSET),
            new SeedEntry("CHARGEBACK_EXPENSE",      LedgerAccount.AccountType.EXPENSE)
    );

    @Override
    public void run(ApplicationArguments args) {
        for (SeedEntry entry : CORE_ACCOUNTS) {
            accountRepository.findByName(entry.name())
                    .orElseGet(() -> accountRepository.save(LedgerAccount.builder()
                            .name(entry.name())
                            .accountType(entry.type())
                            .currency("INR")
                            .build()));
        }
        log.info("Seeded {} core ledger accounts", CORE_ACCOUNTS.size());
    }

    private record SeedEntry(String name, LedgerAccount.AccountType type) {}
}
