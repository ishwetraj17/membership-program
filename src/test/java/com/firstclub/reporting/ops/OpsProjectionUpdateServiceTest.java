package com.firstclub.reporting.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.customer.entity.Customer;
import com.firstclub.dunning.repository.DunningAttemptRepository;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentAttemptRepository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconMismatchStatus;
import com.firstclub.recon.entity.ReconReport;
import com.firstclub.recon.repository.ReconMismatchRepository;
import com.firstclub.recon.repository.ReconReportRepository;
import com.firstclub.reporting.ops.entity.InvoiceSummaryProjection;
import com.firstclub.reporting.ops.entity.PaymentSummaryProjection;
import com.firstclub.reporting.ops.entity.ReconDashboardProjection;
import com.firstclub.reporting.ops.entity.SubscriptionStatusProjection;
import com.firstclub.reporting.ops.repository.InvoiceSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.PaymentSummaryProjectionRepository;
import com.firstclub.reporting.ops.repository.ReconDashboardProjectionRepository;
import com.firstclub.reporting.ops.repository.SubscriptionStatusProjectionRepository;
import com.firstclub.reporting.ops.service.OpsProjectionUpdateService;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OpsProjectionUpdateService}.
 * No Spring context — pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpsProjectionUpdateService — Unit Tests")
class OpsProjectionUpdateServiceTest {

    @Mock private SubscriptionV2Repository             subscriptionRepo;
    @Mock private InvoiceRepository                    invoiceRepo;
    @Mock private PaymentIntentV2Repository            intentRepo;
    @Mock private PaymentAttemptRepository             attemptRepo;
    @Mock private PaymentRepository                    paymentRepo;
    @Mock private DunningAttemptRepository             dunningAttemptRepo;
    @Mock private ReconReportRepository                reconReportRepo;
    @Mock private ReconMismatchRepository              reconMismatchRepo;
    @Mock private SubscriptionStatusProjectionRepository subStatusRepo;
    @Mock private InvoiceSummaryProjectionRepository   invoiceSummaryRepo;
    @Mock private PaymentSummaryProjectionRepository   paymentSummaryRepo;
    @Mock private ReconDashboardProjectionRepository   reconDashboardRepo;
    @Spy  private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OpsProjectionUpdateService service;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DomainEvent event(String type, String payload) {
        return DomainEvent.builder()
                .id(1L)
                .eventType(type)
                .merchantId(1L)
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private MerchantAccount merchant(long id) {
        MerchantAccount m = new MerchantAccount();
        m.setId(id);
        return m;
    }

    private Customer customer(long id) {
        Customer c = new Customer();
        c.setId(id);
        return c;
    }

    private Invoice invoice(long id, long merchantId, long userId, InvoiceStatus status) {
        Invoice inv = new Invoice();
        inv.setId(id);
        inv.setMerchantId(merchantId);
        inv.setUserId(userId);
        inv.setInvoiceNumber("INV-" + id);
        inv.setStatus(status);
        inv.setSubtotal(new BigDecimal("90.00"));
        inv.setTaxTotal(new BigDecimal("10.00"));
        inv.setGrandTotal(new BigDecimal("100.00"));
        inv.setDueDate(LocalDate.now().plusDays(30).atStartOfDay());
        inv.setUpdatedAt(LocalDateTime.now());
        return inv;
    }

    // ── Invoice summary projection ────────────────────────────────────────────

    @Nested
    @DisplayName("applyEventToInvoiceSummaryProjection")
    class InvoiceSummaryTests {

        @Test
        @DisplayName("INVOICE_CREATED — upserts invoice summary row")
        void invoiceCreated_upsertsInvoiceSummary() {
            Invoice inv = invoice(42L, 1L, 10L, InvoiceStatus.OPEN);
            when(invoiceRepo.findById(42L)).thenReturn(Optional.of(inv));
            when(invoiceSummaryRepo.findByMerchantIdAndInvoiceId(1L, 42L)).thenReturn(Optional.empty());
            when(invoiceSummaryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToInvoiceSummaryProjection(
                    event(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":42}"));

            ArgumentCaptor<InvoiceSummaryProjection> captor = ArgumentCaptor.forClass(InvoiceSummaryProjection.class);
            verify(invoiceSummaryRepo).save(captor.capture());
            InvoiceSummaryProjection saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo("OPEN");
            assertThat(saved.getMerchantId()).isEqualTo(1L);
            assertThat(saved.getInvoiceId()).isEqualTo(42L);
            assertThat(saved.getGrandTotal()).isEqualByComparingTo("100.00");
            assertThat(saved.isOverdueFlag()).isFalse();
            assertThat(saved.getPaidAt()).isNull();
        }

        @Test
        @DisplayName("PAYMENT_SUCCEEDED — marks invoice PAID and sets paidAt")
        void paymentSucceeded_marksInvoicePaid() {
            Invoice inv = invoice(99L, 1L, 10L, InvoiceStatus.PAID);
            inv.setUpdatedAt(LocalDateTime.of(2025, 6, 1, 12, 0));
            when(invoiceRepo.findById(99L)).thenReturn(Optional.of(inv));
            when(invoiceSummaryRepo.findByMerchantIdAndInvoiceId(1L, 99L)).thenReturn(Optional.empty());
            when(invoiceSummaryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToInvoiceSummaryProjection(
                    event(DomainEventTypes.PAYMENT_SUCCEEDED, "{\"invoiceId\":99}"));

            ArgumentCaptor<InvoiceSummaryProjection> captor = ArgumentCaptor.forClass(InvoiceSummaryProjection.class);
            verify(invoiceSummaryRepo).save(captor.capture());
            InvoiceSummaryProjection saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo("PAID");
            assertThat(saved.getPaidAt()).isNotNull();
        }

        @Test
        @DisplayName("invoice with null merchantId — silently skipped")
        void noMerchantId_invoiceSkipped() {
            Invoice inv = invoice(5L, 0L, 10L, InvoiceStatus.OPEN);
            inv.setMerchantId(null);
            when(invoiceRepo.findById(5L)).thenReturn(Optional.of(inv));

            service.applyEventToInvoiceSummaryProjection(
                    event(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":5}"));

            verifyNoInteractions(invoiceSummaryRepo);
        }

        @Test
        @DisplayName("OPEN invoice past due date — overdueFlag = true")
        void openInvoicePastDue_overdueFlag() {
            Invoice inv = invoice(77L, 1L, 10L, InvoiceStatus.OPEN);
            inv.setDueDate(LocalDate.now().minusDays(1).atStartOfDay());
            when(invoiceRepo.findById(77L)).thenReturn(Optional.of(inv));
            when(invoiceSummaryRepo.findByMerchantIdAndInvoiceId(1L, 77L)).thenReturn(Optional.empty());
            when(invoiceSummaryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToInvoiceSummaryProjection(
                    event(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":77}"));

            ArgumentCaptor<InvoiceSummaryProjection> captor = ArgumentCaptor.forClass(InvoiceSummaryProjection.class);
            verify(invoiceSummaryRepo).save(captor.capture());
            assertThat(captor.getValue().isOverdueFlag()).isTrue();
        }
    }

    // ── Subscription status projection ────────────────────────────────────────

    @Nested
    @DisplayName("applyEventToSubscriptionStatusProjection")
    class SubscriptionStatusTests {

        private SubscriptionV2 makeSubscription(long id, long merchantId, long customerId, SubscriptionStatusV2 status) {
            SubscriptionV2 sub = new SubscriptionV2();
            sub.setId(id);
            sub.setMerchant(merchant(merchantId));
            sub.setCustomer(customer(customerId));
            sub.setStatus(status);
            sub.setNextBillingAt(LocalDateTime.now().plusDays(30));
            return sub;
        }

        @Test
        @DisplayName("SUBSCRIPTION_ACTIVATED — upserts status row correctly")
        void subscriptionActivated_createsStatusRow() {
            SubscriptionV2 sub = makeSubscription(7L, 1L, 20L, SubscriptionStatusV2.ACTIVE);
            when(subscriptionRepo.findById(7L)).thenReturn(Optional.of(sub));
            when(invoiceRepo.findBySubscriptionId(7L)).thenReturn(Collections.emptyList());
            when(dunningAttemptRepo.findBySubscriptionId(7L)).thenReturn(Collections.emptyList());
            when(intentRepo.findBySubscriptionId(7L)).thenReturn(Collections.emptyList());
            when(subStatusRepo.findByMerchantIdAndSubscriptionId(1L, 7L)).thenReturn(Optional.empty());
            when(subStatusRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToSubscriptionStatusProjection(
                    event(DomainEventTypes.SUBSCRIPTION_ACTIVATED, "{\"subscriptionId\":7}"));

            ArgumentCaptor<SubscriptionStatusProjection> captor = ArgumentCaptor.forClass(SubscriptionStatusProjection.class);
            verify(subStatusRepo).save(captor.capture());
            SubscriptionStatusProjection saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(saved.getMerchantId()).isEqualTo(1L);
            assertThat(saved.getCustomerId()).isEqualTo(20L);
            assertThat(saved.getUnpaidInvoiceCount()).isZero();
            assertThat(saved.getDunningState()).isNull();
            assertThat(saved.getLastPaymentStatus()).isNull();
        }

        @Test
        @DisplayName("SUBSCRIPTION_CANCELLED — updates existing row to CANCELLED")
        void subscriptionCancelled_updatesStatus() {
            SubscriptionV2 sub = makeSubscription(8L, 1L, 20L, SubscriptionStatusV2.CANCELLED);
            SubscriptionStatusProjection existing = SubscriptionStatusProjection.builder()
                    .merchantId(1L).subscriptionId(8L).status("ACTIVE").build();

            when(subscriptionRepo.findById(8L)).thenReturn(Optional.of(sub));
            when(invoiceRepo.findBySubscriptionId(8L)).thenReturn(Collections.emptyList());
            when(dunningAttemptRepo.findBySubscriptionId(8L)).thenReturn(Collections.emptyList());
            when(intentRepo.findBySubscriptionId(8L)).thenReturn(Collections.emptyList());
            when(subStatusRepo.findByMerchantIdAndSubscriptionId(1L, 8L)).thenReturn(Optional.of(existing));
            when(subStatusRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToSubscriptionStatusProjection(
                    event(DomainEventTypes.SUBSCRIPTION_CANCELLED, "{\"subscriptionId\":8}"));

            ArgumentCaptor<SubscriptionStatusProjection> captor = ArgumentCaptor.forClass(SubscriptionStatusProjection.class);
            verify(subStatusRepo).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("event with no subscriptionId — skipped")
        void noSubscriptionId_skipped() {
            service.applyEventToSubscriptionStatusProjection(
                    event(DomainEventTypes.SETTLEMENT_COMPLETED, "{}"));

            verifyNoInteractions(subStatusRepo);
        }
    }

    // ── Payment summary projection ────────────────────────────────────────────

    @Nested
    @DisplayName("applyEventToPaymentSummaryProjection")
    class PaymentSummaryTests {

        private PaymentIntentV2 makeIntent(long id, long merchantId, long customerId) {
            PaymentIntentV2 intent = new PaymentIntentV2();
            intent.setId(id);
            intent.setMerchant(merchant(merchantId));
            intent.setCustomer(customer(customerId));
            intent.setInvoiceId(50L);
            // Use a status that has .name()
            intent.setStatus(com.firstclub.payments.entity.PaymentIntentStatusV2.REQUIRES_PAYMENT_METHOD);
            return intent;
        }

        @Test
        @DisplayName("PAYMENT_INTENT_CREATED — upserts payment summary row")
        void paymentIntentCreated_createsPaymentSummary() {
            PaymentIntentV2 intent = makeIntent(30L, 1L, 20L);
            when(intentRepo.findById(30L)).thenReturn(Optional.of(intent));
            when(paymentRepo.findByPaymentIntentId(30L)).thenReturn(Collections.emptyList());
            when(attemptRepo.countByPaymentIntentId(30L)).thenReturn(0);
            when(attemptRepo.findByPaymentIntentIdOrderByAttemptNumberAsc(30L)).thenReturn(Collections.emptyList());
            when(paymentSummaryRepo.findByMerchantIdAndPaymentIntentId(1L, 30L)).thenReturn(Optional.empty());
            when(paymentSummaryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToPaymentSummaryProjection(
                    event(DomainEventTypes.PAYMENT_INTENT_CREATED, "{\"paymentIntentId\":30}"));

            ArgumentCaptor<PaymentSummaryProjection> captor = ArgumentCaptor.forClass(PaymentSummaryProjection.class);
            verify(paymentSummaryRepo).save(captor.capture());
            PaymentSummaryProjection saved = captor.getValue();
            assertThat(saved.getMerchantId()).isEqualTo(1L);
            assertThat(saved.getPaymentIntentId()).isEqualTo(30L);
            assertThat(saved.getAttemptCount()).isZero();
            assertThat(saved.getCapturedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("REFUND_COMPLETED — updates refunded amount from Payment row")
        void refundCompleted_updatesRefundAmount() {
            PaymentIntentV2 intent = makeIntent(31L, 1L, 20L);
            Payment payment = new Payment();
            payment.setCapturedAmount(new BigDecimal("200.00"));
            payment.setRefundedAmount(new BigDecimal("50.00"));
            payment.setDisputedAmount(BigDecimal.ZERO);

            when(intentRepo.findById(31L)).thenReturn(Optional.of(intent));
            when(paymentRepo.findByPaymentIntentId(31L)).thenReturn(List.of(payment));
            when(attemptRepo.countByPaymentIntentId(31L)).thenReturn(1);
            when(attemptRepo.findByPaymentIntentIdOrderByAttemptNumberAsc(31L)).thenReturn(Collections.emptyList());
            when(paymentSummaryRepo.findByMerchantIdAndPaymentIntentId(1L, 31L)).thenReturn(Optional.empty());
            when(paymentSummaryRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToPaymentSummaryProjection(
                    event(DomainEventTypes.REFUND_COMPLETED, "{\"paymentIntentId\":31}"));

            ArgumentCaptor<PaymentSummaryProjection> captor = ArgumentCaptor.forClass(PaymentSummaryProjection.class);
            verify(paymentSummaryRepo).save(captor.capture());
            PaymentSummaryProjection saved = captor.getValue();
            assertThat(saved.getCapturedAmount()).isEqualByComparingTo("200.00");
            assertThat(saved.getRefundedAmount()).isEqualByComparingTo("50.00");
        }
    }

    // ── Recon dashboard projection ────────────────────────────────────────────

    @Nested
    @DisplayName("applyEventToReconDashboardProjection / upsertReconDashboardProjection")
    class ReconDashboardTests {

        @Test
        @DisplayName("RECON_COMPLETED — upserts dashboard row with layer counts")
        void reconCompleted_updatesDashboard() {
            LocalDate date = LocalDate.of(2025, 6, 15);
            ReconReport report = new ReconReport();
            report.setId(1L);
            report.setReportDate(date);
            report.setExpectedTotal(new BigDecimal("1000.00"));
            report.setActualTotal(new BigDecimal("980.00"));

            ReconMismatch l2open = mismatch(MismatchType.PAYMENT_LEDGER_VARIANCE, ReconMismatchStatus.OPEN);
            ReconMismatch l3open = mismatch(MismatchType.LEDGER_BATCH_VARIANCE, ReconMismatchStatus.OPEN);
            ReconMismatch l3resolved = mismatch(MismatchType.LEDGER_BATCH_VARIANCE, ReconMismatchStatus.RESOLVED);

            when(reconReportRepo.findByReportDate(date)).thenReturn(Optional.of(report));
            when(reconMismatchRepo.findByReportId(1L)).thenReturn(List.of(l2open, l3open, l3resolved));
            when(reconDashboardRepo.findByMerchantIdIsNullAndBusinessDate(date)).thenReturn(Optional.empty());
            when(reconDashboardRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.applyEventToReconDashboardProjection(
                    event(DomainEventTypes.RECON_COMPLETED, "{\"reportDate\":\"2025-06-15\"}"));

            ArgumentCaptor<ReconDashboardProjection> captor = ArgumentCaptor.forClass(ReconDashboardProjection.class);
            verify(reconDashboardRepo).save(captor.capture());
            ReconDashboardProjection saved = captor.getValue();
            assertThat(saved.getLayer2Open()).isEqualTo(1);
            assertThat(saved.getLayer3Open()).isEqualTo(1);
            assertThat(saved.getLayer4Open()).isZero();
            assertThat(saved.getResolvedCount()).isEqualTo(1);
            assertThat(saved.getUnresolvedAmount()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("missing reportDate in payload — skips silently")
        void missingReportDate_skipped() {
            service.applyEventToReconDashboardProjection(
                    event(DomainEventTypes.RECON_COMPLETED, "{}"));
            verifyNoInteractions(reconDashboardRepo);
        }

        @Test
        @DisplayName("upsertReconDashboardProjection — idempotent on second call (updates existing row)")
        void rebuild_recon_idempotent() {
            LocalDate date = LocalDate.of(2025, 5, 1);
            ReconReport report = new ReconReport();
            report.setId(2L);
            report.setReportDate(date);
            report.setExpectedTotal(new BigDecimal("500.00"));
            report.setActualTotal(new BigDecimal("500.00"));

            ReconDashboardProjection existing = ReconDashboardProjection.builder()
                    .businessDate(date).layer2Open(3).layer3Open(0).layer4Open(0)
                    .resolvedCount(5).unresolvedAmount(new BigDecimal("15.00"))
                    .build();

            when(reconReportRepo.findByReportDate(date)).thenReturn(Optional.of(report));
            when(reconMismatchRepo.findByReportId(2L)).thenReturn(Collections.emptyList());
            when(reconDashboardRepo.findByMerchantIdIsNullAndBusinessDate(date)).thenReturn(Optional.of(existing));
            when(reconDashboardRepo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.upsertReconDashboardProjection(date);

            ArgumentCaptor<ReconDashboardProjection> captor = ArgumentCaptor.forClass(ReconDashboardProjection.class);
            verify(reconDashboardRepo).save(captor.capture());
            // All counters reset from re-read (no mismatches → all zero)
            assertThat(captor.getValue().getLayer2Open()).isZero();
            assertThat(captor.getValue().getUnresolvedAmount()).isEqualByComparingTo("0.00");
        }

        private ReconMismatch mismatch(MismatchType type, ReconMismatchStatus status) {
            ReconMismatch m = new ReconMismatch();
            m.setType(type);
            m.setStatus(status);
            return m;
        }
    }
}
