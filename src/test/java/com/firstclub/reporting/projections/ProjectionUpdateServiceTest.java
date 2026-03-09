package com.firstclub.reporting.projections;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.reporting.projections.entity.CustomerBillingSummaryProjection;
import com.firstclub.reporting.projections.entity.MerchantDailyKpiProjection;
import com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository;
import com.firstclub.reporting.projections.service.ProjectionUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProjectionUpdateService}.
 * No Spring context — pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectionUpdateService — Unit Tests")
class ProjectionUpdateServiceTest {

    @Mock private CustomerBillingSummaryProjectionRepository billingSummaryRepo;
    @Mock private MerchantDailyKpiProjectionRepository       kpiRepo;
    @Spy  private ObjectMapper                               objectMapper = new ObjectMapper();

    @InjectMocks
    private ProjectionUpdateService service;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private DomainEvent event(String type, Long merchantId, String payload) {
        return DomainEvent.builder()
                .id(1L)
                .eventType(type)
                .merchantId(merchantId)
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private CustomerBillingSummaryProjection existingProj(Long merchantId, Long customerId) {
        return CustomerBillingSummaryProjection.builder()
                .merchantId(merchantId)
                .customerId(customerId)
                .unpaidInvoicesCount(2)
                .activeSubscriptionsCount(1)
                .totalPaidAmount(new BigDecimal("100.00"))
                .totalRefundedAmount(BigDecimal.ZERO)
                .build();
    }

    // ── Customer billing summary: applyEventToCustomerBillingProjection ──────

    @Nested
    @DisplayName("applyEventToCustomerBillingProjection")
    class CustomerBillingTests {

        @Test
        @DisplayName("INVOICE_CREATED — increments unpaidInvoicesCount and saves")
        void invoiceCreated_incrementsUnpaidCount() {
            when(billingSummaryRepo.findByMerchantIdAndCustomerId(1L, 10L))
                    .thenReturn(Optional.empty());
            when(billingSummaryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.INVOICE_CREATED, 1L, "{\"customerId\": 10}"));

            ArgumentCaptor<CustomerBillingSummaryProjection> cap =
                    ArgumentCaptor.forClass(CustomerBillingSummaryProjection.class);
            verify(billingSummaryRepo).save(cap.capture());
            assertThat(cap.getValue().getUnpaidInvoicesCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("PAYMENT_SUCCEEDED — updates totalPaidAmount, decrements unpaidInvoicesCount, sets lastPaymentAt")
        void paymentSucceeded_updatesTotals() {
            CustomerBillingSummaryProjection proj = existingProj(1L, 10L);
            when(billingSummaryRepo.findByMerchantIdAndCustomerId(1L, 10L))
                    .thenReturn(Optional.of(proj));
            when(billingSummaryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.PAYMENT_SUCCEEDED, 1L,
                            "{\"customerId\": 10, \"amount\": \"500.00\"}"));

            ArgumentCaptor<CustomerBillingSummaryProjection> cap =
                    ArgumentCaptor.forClass(CustomerBillingSummaryProjection.class);
            verify(billingSummaryRepo).save(cap.capture());
            CustomerBillingSummaryProjection saved = cap.getValue();
            assertThat(saved.getTotalPaidAmount()).isEqualByComparingTo("600.00"); // 100 + 500
            assertThat(saved.getUnpaidInvoicesCount()).isEqualTo(1);              // 2 - 1
            assertThat(saved.getLastPaymentAt()).isNotNull();
        }

        @Test
        @DisplayName("REFUND_COMPLETED — increments totalRefundedAmount")
        void refundCompleted_incrementsRefundAmount() {
            when(billingSummaryRepo.findByMerchantIdAndCustomerId(1L, 10L))
                    .thenReturn(Optional.of(existingProj(1L, 10L)));
            when(billingSummaryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.REFUND_COMPLETED, 1L,
                            "{\"customerId\": 10, \"amount\": \"50.00\"}"));

            ArgumentCaptor<CustomerBillingSummaryProjection> cap =
                    ArgumentCaptor.forClass(CustomerBillingSummaryProjection.class);
            verify(billingSummaryRepo).save(cap.capture());
            assertThat(cap.getValue().getTotalRefundedAmount()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("SUBSCRIPTION_ACTIVATED — increments activeSubscriptionsCount")
        void subscriptionActivated_incrementsCount() {
            when(billingSummaryRepo.findByMerchantIdAndCustomerId(1L, 10L))
                    .thenReturn(Optional.of(existingProj(1L, 10L)));
            when(billingSummaryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.SUBSCRIPTION_ACTIVATED, 1L, "{\"customerId\": 10}"));

            ArgumentCaptor<CustomerBillingSummaryProjection> cap =
                    ArgumentCaptor.forClass(CustomerBillingSummaryProjection.class);
            verify(billingSummaryRepo).save(cap.capture());
            assertThat(cap.getValue().getActiveSubscriptionsCount()).isEqualTo(2); // 1 + 1
        }

        @Test
        @DisplayName("SUBSCRIPTION_CANCELLED — decrements activeSubscriptionsCount (never below zero)")
        void subscriptionCancelled_decrementsCount() {
            when(billingSummaryRepo.findByMerchantIdAndCustomerId(1L, 10L))
                    .thenReturn(Optional.of(existingProj(1L, 10L)));
            when(billingSummaryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.SUBSCRIPTION_CANCELLED, 1L, "{\"customerId\": 10}"));

            ArgumentCaptor<CustomerBillingSummaryProjection> cap =
                    ArgumentCaptor.forClass(CustomerBillingSummaryProjection.class);
            verify(billingSummaryRepo).save(cap.capture());
            assertThat(cap.getValue().getActiveSubscriptionsCount()).isEqualTo(0); // 1 - 1
        }

        @Test
        @DisplayName("No merchantId — event is skipped, no save")
        void noMerchantId_skipped() {
            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.INVOICE_CREATED, null, "{\"customerId\": 10}"));

            verify(billingSummaryRepo, never()).save(any());
        }

        @Test
        @DisplayName("No customerId in payload — event is skipped, no save")
        void noCustomerId_skipped() {
            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.INVOICE_CREATED, 1L, "{}"));

            verify(billingSummaryRepo, never()).save(any());
        }

        @Test
        @DisplayName("Unhandled event type — no save")
        void unhandledEventType_noSave() {
            service.applyEventToCustomerBillingProjection(
                    event(DomainEventTypes.RISK_DECISION_MADE, 1L, "{\"customerId\": 10}"));

            verify(billingSummaryRepo, never()).save(any());
        }
    }

    // ── Merchant daily KPI: applyEventToMerchantDailyKpi ────────────────────

    @Nested
    @DisplayName("applyEventToMerchantDailyKpi")
    class MerchantKpiTests {

        private final LocalDate today = LocalDate.now();

        private MerchantDailyKpiProjection existingKpi() {
            return MerchantDailyKpiProjection.builder()
                    .merchantId(5L)
                    .businessDate(today)
                    .invoicesCreated(2)
                    .paymentsCaptured(1)
                    .revenueRecognized(new BigDecimal("200.00"))
                    .build();
        }

        @Test
        @DisplayName("INVOICE_CREATED — increments invoicesCreated")
        void invoiceCreated_incrementsInvoicesCreated() {
            when(kpiRepo.findByMerchantIdAndBusinessDate(5L, today)).thenReturn(Optional.of(existingKpi()));
            when(kpiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToMerchantDailyKpi(event(DomainEventTypes.INVOICE_CREATED, 5L, "{}"));

            ArgumentCaptor<MerchantDailyKpiProjection> cap =
                    ArgumentCaptor.forClass(MerchantDailyKpiProjection.class);
            verify(kpiRepo).save(cap.capture());
            assertThat(cap.getValue().getInvoicesCreated()).isEqualTo(3); // 2 + 1
        }

        @Test
        @DisplayName("PAYMENT_SUCCEEDED — increments invoicesPaid, paymentsCaptured, and revenueRecognized")
        void paymentSucceeded_incrementsMultipleCounters() {
            when(kpiRepo.findByMerchantIdAndBusinessDate(5L, today)).thenReturn(Optional.of(existingKpi()));
            when(kpiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToMerchantDailyKpi(
                    event(DomainEventTypes.PAYMENT_SUCCEEDED, 5L, "{\"amount\": \"300.00\"}"));

            ArgumentCaptor<MerchantDailyKpiProjection> cap =
                    ArgumentCaptor.forClass(MerchantDailyKpiProjection.class);
            verify(kpiRepo).save(cap.capture());
            MerchantDailyKpiProjection saved = cap.getValue();
            assertThat(saved.getInvoicesPaid()).isEqualTo(1);
            assertThat(saved.getPaymentsCaptured()).isEqualTo(2);             // 1 + 1
            assertThat(saved.getRevenueRecognized()).isEqualByComparingTo("500.00"); // 200 + 300
        }

        @Test
        @DisplayName("DISPUTE_OPENED — increments disputesOpened")
        void disputeOpened_incrementsDisputes() {
            when(kpiRepo.findByMerchantIdAndBusinessDate(5L, today)).thenReturn(Optional.empty());
            when(kpiRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.applyEventToMerchantDailyKpi(event(DomainEventTypes.DISPUTE_OPENED, 5L, "{}"));

            ArgumentCaptor<MerchantDailyKpiProjection> cap =
                    ArgumentCaptor.forClass(MerchantDailyKpiProjection.class);
            verify(kpiRepo).save(cap.capture());
            assertThat(cap.getValue().getDisputesOpened()).isEqualTo(1);
        }

        @Test
        @DisplayName("No merchantId — event is skipped, no save")
        void noMerchantId_skipped() {
            service.applyEventToMerchantDailyKpi(event(DomainEventTypes.INVOICE_CREATED, null, "{}"));

            verify(kpiRepo, never()).save(any());
        }
    }
}
