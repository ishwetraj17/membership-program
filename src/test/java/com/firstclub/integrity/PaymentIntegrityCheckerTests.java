package com.firstclub.integrity;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.integrity.checkers.*;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.entity.LedgerEntryType;
import com.firstclub.ledger.entity.LedgerReferenceType;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.payments.disputes.entity.Dispute;
import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.disputes.repository.DisputeRepository;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.repository.PaymentRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for payment-related invariant checkers.
 */
@ExtendWith(MockitoExtension.class)
class PaymentIntegrityCheckerTests {

    // ── PaymentHasSingleLedgerEntryChecker ───────────────────────────────────

    @Nested
    class PaymentHasSingleLedgerEntryCheckerTest {

        @Mock PaymentRepository     paymentRepo;
        @Mock LedgerEntryRepository ledgerEntryRepo;
        @InjectMocks PaymentHasSingleLedgerEntryChecker checker;

        @Test
        void pass_whenEachCapturedPaymentHasExactlyOneLedgerEntry() {
            Payment payment = Payment.builder().id(1L).status(PaymentStatus.CAPTURED).build();
            LedgerEntry entry = LedgerEntry.builder()
                    .id(10L).entryType(LedgerEntryType.PAYMENT_CAPTURED)
                    .referenceType(LedgerReferenceType.PAYMENT).referenceId(1L).build();

            when(paymentRepo.findByStatus(PaymentStatus.CAPTURED)).thenReturn(List.of(payment));
            when(ledgerEntryRepo.findByReferenceTypeAndReferenceId(LedgerReferenceType.PAYMENT, 1L))
                    .thenReturn(List.of(entry));

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenCapturedPaymentHasZeroEntries() {
            Payment payment = Payment.builder().id(2L).status(PaymentStatus.CAPTURED).build();
            when(paymentRepo.findByStatus(PaymentStatus.CAPTURED)).thenReturn(List.of(payment));
            when(ledgerEntryRepo.findByReferenceTypeAndReferenceId(LedgerReferenceType.PAYMENT, 2L))
                    .thenReturn(List.of());

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }

        @Test
        void fail_whenCapturedPaymentHasTwoEntries() {
            Payment payment = Payment.builder().id(3L).status(PaymentStatus.CAPTURED).build();
            LedgerEntry e1 = LedgerEntry.builder().id(20L).entryType(LedgerEntryType.PAYMENT_CAPTURED)
                    .referenceType(LedgerReferenceType.PAYMENT).referenceId(3L).build();
            LedgerEntry e2 = LedgerEntry.builder().id(21L).entryType(LedgerEntryType.PAYMENT_CAPTURED)
                    .referenceType(LedgerReferenceType.PAYMENT).referenceId(3L).build();

            when(paymentRepo.findByStatus(PaymentStatus.CAPTURED)).thenReturn(List.of(payment));
            when(ledgerEntryRepo.findByReferenceTypeAndReferenceId(LedgerReferenceType.PAYMENT, 3L))
                    .thenReturn(List.of(e1, e2));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
        }
    }

    // ── RefundAmountChainChecker ─────────────────────────────────────────────

    @Nested
    class RefundAmountChainCheckerTest {

        @Mock PaymentRepository paymentRepo;
        @InjectMocks RefundAmountChainChecker checker;

        @Test
        void pass_whenNoCapacityViolations() {
            when(paymentRepo.findCapacityViolations()).thenReturn(List.of());

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenRefundExceedsCapturedAmount() {
            Payment bad = Payment.builder()
                    .id(5L)
                    .capturedAmount(new BigDecimal("100.00"))
                    .refundedAmount(new BigDecimal("80.00"))
                    .disputedAmount(new BigDecimal("50.00"))   // 80+50 > 100
                    .build();
            when(paymentRepo.findCapacityViolations()).thenReturn(List.of(bad));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getSeverity()).isEqualTo(InvariantSeverity.CRITICAL);
        }
    }

    // ── DisputeReserveCompletenessChecker ────────────────────────────────────

    @Nested
    class DisputeReserveCompletenessCheckerTest {

        @Mock DisputeRepository disputeRepo;
        @InjectMocks DisputeReserveCompletenessChecker checker;

        @Test
        void pass_whenAllActiveDisputesHaveReservePosted() {
            Dispute d = Dispute.builder().id(1L)
                    .status(DisputeStatus.OPEN).reservePosted(true).build();
            when(disputeRepo.findAll()).thenReturn(List.of(d));

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenOpenDisputeLacksReserve() {
            Dispute d = Dispute.builder().id(2L)
                    .status(DisputeStatus.OPEN).reservePosted(false).build();
            when(disputeRepo.findAll()).thenReturn(List.of(d));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }

        @Test
        void pass_whenClosedDisputeDoesNotHaveReservePosted() {
            // CLOSED disputes are not checked — only OPEN and UNDER_REVIEW
            Dispute d = Dispute.builder().id(3L)
                    .status(DisputeStatus.CLOSED).reservePosted(false).build();
            when(disputeRepo.findAll()).thenReturn(List.of(d));

            assertThat(checker.check().isPassed()).isTrue();
        }
    }

    // ── SettlementLedgerCompletenessChecker ──────────────────────────────────

    @Nested
    class SettlementLedgerCompletenessCheckerTest {

        @Mock LedgerEntryRepository ledgerEntryRepo;
        @InjectMocks SettlementLedgerCompletenessChecker checker;

        @Test
        void pass_whenAllSettlementEntriesHaveReferenceId() {
            LedgerEntry entry = LedgerEntry.builder()
                    .id(100L).entryType(LedgerEntryType.SETTLEMENT).referenceId(42L).build();
            when(ledgerEntryRepo.findByEntryType(LedgerEntryType.SETTLEMENT)).thenReturn(List.of(entry));

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenSettlementEntryHasNullReferenceId() {
            LedgerEntry orphan = LedgerEntry.builder()
                    .id(101L).entryType(LedgerEntryType.SETTLEMENT).referenceId(null).build();
            when(ledgerEntryRepo.findByEntryType(LedgerEntryType.SETTLEMENT)).thenReturn(List.of(orphan));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }
    }
}
