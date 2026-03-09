package com.firstclub.integrity;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.integrity.checkers.*;
import com.firstclub.ledger.entity.LedgerEntry;
import com.firstclub.ledger.repository.LedgerEntryRepository;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.entity.WebhookEvent;
import com.firstclub.payments.repository.WebhookEventRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Unit tests for system / cross-entity invariant checkers.
 */
@ExtendWith(MockitoExtension.class)
class SystemIntegrityCheckerTests {

    // ── NoFutureLedgerEntryChecker ───────────────────────────────────────────

    @Nested
    class NoFutureLedgerEntryCheckerTest {

        @Mock LedgerEntryRepository ledgerEntryRepo;
        @InjectMocks NoFutureLedgerEntryChecker checker;

        @Test
        void pass_whenNoFutureEntries() {
            when(ledgerEntryRepo.findByCreatedAtAfter(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenFutureEntryExists() {
            LedgerEntry future = LedgerEntry.builder()
                    .id(555L)
                    .createdAt(LocalDateTime.now().plusDays(1))
                    .build();
            when(ledgerEntryRepo.findByCreatedAtAfter(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(future));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
            assertThat(r.getSeverity()).isEqualTo(InvariantSeverity.HIGH);
        }
    }

    // ── OutboxToLedgerGapChecker ─────────────────────────────────────────────

    @Nested
    class OutboxToLedgerGapCheckerTest {

        @Mock OutboxEventRepository outboxRepo;
        @InjectMocks OutboxToLedgerGapChecker checker;

        @Test
        void pass_whenNoFailedOutboxEvents() {
            when(outboxRepo.findByStatus(OutboxEventStatus.FAILED)).thenReturn(List.of());

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenFailedOutboxEventExists() {
            OutboxEvent failed = new OutboxEvent();
            failed.setId(1L);
            failed.setEventType("payment.captured");
            failed.setAggregateId("payment-99");
            failed.setStatus(OutboxEventStatus.FAILED);

            when(outboxRepo.findByStatus(OutboxEventStatus.FAILED)).thenReturn(List.of(failed));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }
    }

    // ── WebhookDuplicateProcessingChecker ────────────────────────────────────

    @Nested
    class WebhookDuplicateProcessingCheckerTest {

        @Mock WebhookEventRepository webhookRepo;
        @InjectMocks WebhookDuplicateProcessingChecker checker;

        @Test
        void pass_whenNoStuckWebhooks() {
            when(webhookRepo.findStuckWebhooks(anyInt())).thenReturn(List.of());

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenStuckWebhookExists() {
            WebhookEvent stuck = new WebhookEvent();
            stuck.setId(77L);
            stuck.setProvider("gateway");
            stuck.setEventId("evt_abc");
            stuck.setEventType("payment.succeeded");
            stuck.setAttempts(15);

            when(webhookRepo.findStuckWebhooks(anyInt())).thenReturn(List.of(stuck));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }
    }

    // ── SubscriptionInvoicePeriodOverlapChecker ──────────────────────────────

    @Nested
    class SubscriptionInvoicePeriodOverlapCheckerTest {

        @Mock InvoiceRepository invoiceRepo;
        @InjectMocks SubscriptionInvoicePeriodOverlapChecker checker;

        @Test
        void pass_whenNoInvoicePeriodsOverlap() {
            when(invoiceRepo.findDistinctSubscriptionIds()).thenReturn(List.of(10L));

            Invoice inv1 = Invoice.builder().id(1L).subscriptionId(10L)
                    .periodStart(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .periodEnd(LocalDateTime.of(2024, 1, 31, 23, 59)).build();
            Invoice inv2 = Invoice.builder().id(2L).subscriptionId(10L)
                    .periodStart(LocalDateTime.of(2024, 2, 1, 0, 0))
                    .periodEnd(LocalDateTime.of(2024, 2, 29, 23, 59)).build();

            when(invoiceRepo.findBySubscriptionId(10L)).thenReturn(List.of(inv1, inv2));

            assertThat(checker.check().isPassed()).isTrue();
        }

        @Test
        void fail_whenTwoInvoicePeriodsOverlap() {
            when(invoiceRepo.findDistinctSubscriptionIds()).thenReturn(List.of(20L));

            Invoice inv1 = Invoice.builder().id(10L).subscriptionId(20L)
                    .periodStart(LocalDateTime.of(2024, 1, 1, 0, 0))
                    .periodEnd(LocalDateTime.of(2024, 2, 15, 23, 59)).build(); // ends Feb 15

            Invoice inv2 = Invoice.builder().id(11L).subscriptionId(20L)
                    .periodStart(LocalDateTime.of(2024, 2, 1, 0, 0))           // starts Feb 1 — OVERLAP
                    .periodEnd(LocalDateTime.of(2024, 2, 28, 23, 59)).build();

            when(invoiceRepo.findBySubscriptionId(20L)).thenReturn(List.of(inv1, inv2));

            InvariantResult r = checker.check();

            assertThat(r.isFailed()).isTrue();
            assertThat(r.getViolationCount()).isEqualTo(1);
        }

        @Test
        void pass_whenInvoicesPeriodStartOrEndIsNull() {
            // Invoices without period dates should be skipped
            when(invoiceRepo.findDistinctSubscriptionIds()).thenReturn(List.of(30L));

            Invoice inv = Invoice.builder().id(20L).subscriptionId(30L)
                    .periodStart(null).periodEnd(null).build();

            when(invoiceRepo.findBySubscriptionId(30L)).thenReturn(List.of(inv));

            assertThat(checker.check().isPassed()).isTrue();
        }
    }
}
