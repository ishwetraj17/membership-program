package com.firstclub.integrity;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.integrity.checkers.*;
import com.firstclub.ledger.entity.LedgerAccount;
import com.firstclub.ledger.entity.LineDirection;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.repository.LedgerLineRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ledger-focused invariant checkers.
 */
@ExtendWith(MockitoExtension.class)
class LedgerIntegrityCheckerTests {

    // ── BalanceSheetEquationChecker ──────────────────────────────────────────

    @Nested
    class BalanceSheetEquationCheckerTest {

        @Mock LedgerLineRepository repo;
        @InjectMocks BalanceSheetEquationChecker checker;

        @Test
        void pass_whenDebitsEqualCredits() {
            when(repo.sumByDirection(LineDirection.DEBIT)).thenReturn(new BigDecimal("1000.00"));
            when(repo.sumByDirection(LineDirection.CREDIT)).thenReturn(new BigDecimal("1000.00"));

            InvariantResult r = checker.check();

            assertThat(r.isPassed()).isTrue();
            assertThat(r.getViolationCount()).isZero();
        }

        @Test
        void fail_whenDebitsDoNotEqualCredits() {
            when(repo.sumByDirection(LineDirection.DEBIT)).thenReturn(new BigDecimal("1000.00"));
            when(repo.sumByDirection(LineDirection.CREDIT)).thenReturn(new BigDecimal("900.00"));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
            assertThat(r.getSeverity()).isEqualTo(InvariantSeverity.CRITICAL);
        }
    }

    // ── DeferredRevenueNonNegativeChecker ────────────────────────────────────

    @Nested
    class DeferredRevenueNonNegativeCheckerTest {

        @Mock LedgerAccountRepository accountRepo;
        @Mock LedgerLineRepository    lineRepo;
        @InjectMocks DeferredRevenueNonNegativeChecker checker;

        @Test
        void pass_whenNoSubscriptionLiabilityAccount() {
            when(accountRepo.findByName("SUBSCRIPTION_LIABILITY")).thenReturn(java.util.Optional.empty());

            InvariantResult r = checker.check();

            assertThat(r.isPassed()).isTrue();
        }

        @Test
        void fail_whenDebitsExceedCredits() {
            LedgerAccount account = LedgerAccount.builder().id(1L).name("SUBSCRIPTION_LIABILITY").build();
            when(accountRepo.findByName("SUBSCRIPTION_LIABILITY")).thenReturn(java.util.Optional.of(account));
            when(lineRepo.sumByAccountIdAndDirection(1L, LineDirection.DEBIT)).thenReturn(new BigDecimal("500"));
            when(lineRepo.sumByAccountIdAndDirection(1L, LineDirection.CREDIT)).thenReturn(new BigDecimal("300"));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getSeverity()).isEqualTo(InvariantSeverity.HIGH);
        }

        @Test
        void pass_whenCreditsExceedDebits() {
            LedgerAccount account = LedgerAccount.builder().id(2L).name("SUBSCRIPTION_LIABILITY").build();
            when(accountRepo.findByName("SUBSCRIPTION_LIABILITY")).thenReturn(java.util.Optional.of(account));
            when(lineRepo.sumByAccountIdAndDirection(2L, LineDirection.DEBIT)).thenReturn(new BigDecimal("200"));
            when(lineRepo.sumByAccountIdAndDirection(2L, LineDirection.CREDIT)).thenReturn(new BigDecimal("500"));

            InvariantResult r = checker.check();

            assertThat(r.isPassed()).isTrue();
        }
    }

    // ── AssetAccountNonNegativeChecker ───────────────────────────────────────

    @Nested
    class AssetAccountNonNegativeCheckerTest {

        @Mock LedgerAccountRepository accountRepo;
        @Mock LedgerLineRepository    lineRepo;
        @InjectMocks AssetAccountNonNegativeChecker checker;

        @Test
        void pass_whenAllAssetAccountsHavePositiveBalance() {
            LedgerAccount asset = LedgerAccount.builder().id(10L).name("RECEIVABLES")
                    .accountType(LedgerAccount.AccountType.ASSET).build();
            when(accountRepo.findByAccountType(LedgerAccount.AccountType.ASSET)).thenReturn(List.of(asset));
            when(lineRepo.sumByAccountIdAndDirection(10L, LineDirection.DEBIT)).thenReturn(new BigDecimal("1000"));
            when(lineRepo.sumByAccountIdAndDirection(10L, LineDirection.CREDIT)).thenReturn(new BigDecimal("400"));

            InvariantResult r = checker.check();

            assertThat(r.isPassed()).isTrue();
        }

        @Test
        void fail_whenAssetAccountHasNegativeBalance() {
            LedgerAccount asset = LedgerAccount.builder().id(11L).name("RECEIVABLES")
                    .accountType(LedgerAccount.AccountType.ASSET).build();
            when(accountRepo.findByAccountType(LedgerAccount.AccountType.ASSET)).thenReturn(List.of(asset));
            when(lineRepo.sumByAccountIdAndDirection(11L, LineDirection.DEBIT)).thenReturn(new BigDecimal("100"));
            when(lineRepo.sumByAccountIdAndDirection(11L, LineDirection.CREDIT)).thenReturn(new BigDecimal("300"));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }
    }

    // ── NoOrphanLedgerLineChecker ────────────────────────────────────────────

    @Nested
    class NoOrphanLedgerLineCheckerTest {

        @Mock LedgerLineRepository repo;
        @InjectMocks NoOrphanLedgerLineChecker checker;

        @Test
        void pass_whenNoOrphansExist() {
            when(repo.findOrphans()).thenReturn(List.of());

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenOrphansExist() {
            com.firstclub.ledger.entity.LedgerLine orphan =
                    com.firstclub.ledger.entity.LedgerLine.builder()
                            .id(99L).entryId(999L).build();
            when(repo.findOrphans()).thenReturn(List.of(orphan));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }
    }

    // ── InvoicePaymentAmountConsistencyChecker ───────────────────────────────

    @Nested
    class InvoicePaymentAmountConsistencyCheckerTest {

        @Mock InvoiceRepository invoiceRepo;
        @InjectMocks InvoicePaymentAmountConsistencyChecker checker;

        @Test
        void pass_whenAllPaidInvoiceAmountsAreConsistent() {
            Invoice inv = Invoice.builder()
                    .id(1L)
                    .status(InvoiceStatus.PAID)
                    .subtotal(new BigDecimal("100.00"))
                    .discountTotal(new BigDecimal("10.00"))
                    .creditTotal(BigDecimal.ZERO)
                    .taxTotal(new BigDecimal("5.00"))
                    .grandTotal(new BigDecimal("95.00"))
                    .build();
            when(invoiceRepo.findByStatus(InvoiceStatus.PAID)).thenReturn(List.of(inv));

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenGrandTotalDoesNotMatchFormula() {
            Invoice inv = Invoice.builder()
                    .id(2L)
                    .status(InvoiceStatus.PAID)
                    .subtotal(new BigDecimal("100.00"))
                    .discountTotal(BigDecimal.ZERO)
                    .creditTotal(BigDecimal.ZERO)
                    .taxTotal(BigDecimal.ZERO)
                    .grandTotal(new BigDecimal("150.00"))   // wrong: should be 100
                    .build();
            when(invoiceRepo.findByStatus(InvoiceStatus.PAID)).thenReturn(List.of(inv));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }
    }
}
