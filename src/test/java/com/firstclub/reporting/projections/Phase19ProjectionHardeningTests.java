package com.firstclub.reporting.projections;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.reporting.projections.dto.ConsistencyReport;
import com.firstclub.reporting.projections.dto.ProjectionLagReport;
import com.firstclub.reporting.projections.entity.CustomerPaymentSummaryProjection;
import com.firstclub.reporting.projections.entity.LedgerBalanceProjection;
import com.firstclub.reporting.projections.entity.MerchantRevenueProjection;
import com.firstclub.reporting.projections.repository.CustomerPaymentSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.LedgerBalanceProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantRevenueProjectionRepository;
import com.firstclub.reporting.projections.service.ProjectionConsistencyChecker;
import com.firstclub.reporting.projections.service.ProjectionLagMonitor;
import com.firstclub.reporting.projections.service.ProjectionUpdateService;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 19 unit tests — Projection hardening and denormalized operational read models.
 *
 * <p>Covers:
 * <ul>
 *   <li>Domain event → customer_payment_summary_projection update</li>
 *   <li>Rebuild reconstructs projection from scratch</li>
 *   <li>ProjectionLagMonitor detects stale projections</li>
 *   <li>ProjectionConsistencyChecker finds mismatches between projection and source</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Phase 19 — Projection Hardening & Denormalized Read Models")
class Phase19ProjectionHardeningTests {

    // =========================================================================
    // ProjectionUpdateService — customer_payment_summary
    // =========================================================================

    @Nested
    @DisplayName("ProjectionUpdateService — CustomerPaymentSummary")
    class CustomerPaymentSummaryUpdateTests {

        @Mock CustomerPaymentSummaryProjectionRepository customerPaymentRepo;
        @Mock LedgerBalanceProjectionRepository          ledgerBalanceRepo;
        @Mock MerchantRevenueProjectionRepository        merchantRevenueRepo;
        @Mock com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository billingSummaryRepo;
        @Mock com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository kpiRepo;
        @Mock ObjectMapper objectMapper;
        @InjectMocks ProjectionUpdateService updateService;

        @Test
        @DisplayName("PAYMENT_SUCCEEDED increments successfulPayments and totalChargedMinor")
        void eventUpdatePopulatesCustomerPaymentSummary() throws Exception {
            Long merchantId = 1L;
            Long customerId = 42L;

            // Existing empty projection
            CustomerPaymentSummaryProjection existing = CustomerPaymentSummaryProjection.builder()
                    .merchantId(merchantId)
                    .customerId(customerId)
                    .successfulPayments(0)
                    .totalChargedMinor(0L)
                    .build();

            DomainEvent event = new DomainEvent();
            event.setEventType(DomainEventTypes.PAYMENT_SUCCEEDED);
            event.setMerchantId(merchantId);
            event.setCreatedAt(LocalDateTime.now());
            event.setPayload("{\"customerId\":42,\"amountMinor\":1500}");

            com.fasterxml.jackson.databind.node.ObjectNode payloadNode =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            payloadNode.put("customerId", 42L);
            payloadNode.put("amountMinor", 1500L);

            when(objectMapper.readTree("{\"customerId\":42,\"amountMinor\":1500}"))
                    .thenReturn(payloadNode);
            when(customerPaymentRepo.findByMerchantIdAndCustomerId(merchantId, customerId))
                    .thenReturn(Optional.of(existing));
            when(customerPaymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            updateService.applyEventToCustomerPaymentSummary(event);

            assertThat(existing.getSuccessfulPayments()).isEqualTo(1);
            assertThat(existing.getTotalChargedMinor()).isEqualTo(1500L);
            verify(customerPaymentRepo).save(existing);
        }

        @Test
        @DisplayName("PAYMENT_ATTEMPT_FAILED increments failedPayments")
        void paymentAttemptFailed_incrementsFailedPayments() throws Exception {
            Long merchantId = 2L;
            Long customerId = 7L;

            CustomerPaymentSummaryProjection existing = CustomerPaymentSummaryProjection.builder()
                    .merchantId(merchantId)
                    .customerId(customerId)
                    .failedPayments(2)
                    .build();

            DomainEvent event = new DomainEvent();
            event.setEventType(DomainEventTypes.PAYMENT_ATTEMPT_FAILED);
            event.setMerchantId(merchantId);
            event.setCreatedAt(LocalDateTime.now());
            event.setPayload("{\"customerId\":7}");

            com.fasterxml.jackson.databind.node.ObjectNode payloadNode =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            payloadNode.put("customerId", 7L);

            when(objectMapper.readTree("{\"customerId\":7}")).thenReturn(payloadNode);
            when(customerPaymentRepo.findByMerchantIdAndCustomerId(merchantId, customerId))
                    .thenReturn(Optional.of(existing));
            when(customerPaymentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            updateService.applyEventToCustomerPaymentSummary(event);

            assertThat(existing.getFailedPayments()).isEqualTo(3);
            verify(customerPaymentRepo).save(existing);
        }

        @Test
        @DisplayName("Event without merchantId is silently skipped")
        void eventMissingMerchantId_isSkipped() throws Exception {
            DomainEvent event = new DomainEvent();
            event.setEventType(DomainEventTypes.PAYMENT_SUCCEEDED);
            event.setMerchantId(null);
            event.setPayload("{\"customerId\":99}");

            com.fasterxml.jackson.databind.node.ObjectNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            node.put("customerId", 99L);
            when(objectMapper.readTree("{\"customerId\":99}")).thenReturn(node);

            updateService.applyEventToCustomerPaymentSummary(event);

            verify(customerPaymentRepo, never()).save(any());
        }

        @Test
        @DisplayName("SUBSCRIPTION_ACTIVATED increments activeSubscriptions in MerchantRevenueProjection")
        void subscriptionActivated_incrementsActiveSubscriptions() throws Exception {
            Long merchantId = 5L;
            MerchantRevenueProjection proj = MerchantRevenueProjection.builder()
                    .merchantId(merchantId)
                    .activeSubscriptions(3)
                    .build();

            DomainEvent event = new DomainEvent();
            event.setEventType(DomainEventTypes.SUBSCRIPTION_ACTIVATED);
            event.setMerchantId(merchantId);
            event.setCreatedAt(LocalDateTime.now());
            event.setPayload("{}");

            when(merchantRevenueRepo.findById(merchantId)).thenReturn(Optional.of(proj));
            when(merchantRevenueRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            updateService.applyEventToMerchantRevenue(event);

            assertThat(proj.getActiveSubscriptions()).isEqualTo(4);
            verify(merchantRevenueRepo).save(proj);
        }
    }

    // =========================================================================
    // Rebuild reconstructs projection from scratch
    // =========================================================================

    @Nested
    @DisplayName("ProjectionRebuildService — customer_payment_summary rebuild")
    class RebuildTests {

        @Mock com.firstclub.events.repository.DomainEventRepository              domainEventRepository;
        @Mock com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository billingSummaryRepo;
        @Mock com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository       kpiRepo;
        @Mock ProjectionUpdateService                                             projectionUpdateService;
        @Mock com.firstclub.subscription.repository.SubscriptionV2Repository     subscriptionRepo;
        @Mock com.firstclub.billing.repository.InvoiceRepository                 invoiceRepo;
        @Mock com.firstclub.payments.repository.PaymentIntentV2Repository        intentRepo;
        @Mock com.firstclub.recon.repository.ReconReportRepository               reconReportRepo;
        @Mock com.firstclub.reporting.ops.repository.SubscriptionStatusProjectionRepository subStatusRepo;
        @Mock com.firstclub.reporting.ops.repository.InvoiceSummaryProjectionRepository     invoiceSummaryRepo;
        @Mock com.firstclub.reporting.ops.repository.PaymentSummaryProjectionRepository     paymentSummaryRepo;
        @Mock com.firstclub.reporting.ops.repository.ReconDashboardProjectionRepository     reconDashboardRepo;
        @Mock com.firstclub.reporting.ops.service.OpsProjectionUpdateService                opsProjectionUpdateService;
        @Mock CustomerPaymentSummaryProjectionRepository                         customerPaymentRepo;
        @Mock LedgerBalanceProjectionRepository                                  ledgerBalanceRepo;
        @Mock MerchantRevenueProjectionRepository                                merchantRevenueRepo;
        @InjectMocks com.firstclub.reporting.projections.service.ProjectionRebuildService rebuildService;

        @Test
        @DisplayName("rebuildCustomerPaymentSummary deletes all rows then replays events")
        void rebuildReconstructsProjectionFromScratch() {
            DomainEvent e1 = new DomainEvent();
            e1.setEventType(DomainEventTypes.PAYMENT_SUCCEEDED);
            DomainEvent e2 = new DomainEvent();
            e2.setEventType(DomainEventTypes.PAYMENT_ATTEMPT_FAILED);

            when(domainEventRepository.findAll()).thenReturn(List.of(e1, e2));
            when(customerPaymentRepo.count()).thenReturn(1L);

            var result = rebuildService.rebuildCustomerPaymentSummaryProjection();

            verify(customerPaymentRepo).deleteAllInBatch();
            verify(projectionUpdateService, times(2)).applyEventToCustomerPaymentSummary(any(DomainEvent.class));
            assertThat(result.getProjectionName()).isEqualTo("customer_payment_summary");
            assertThat(result.getEventsProcessed()).isEqualTo(2);
            assertThat(result.getRecordsInProjection()).isEqualTo(1L);
        }

        @Test
        @DisplayName("rebuildMerchantRevenue replays all events after truncation")
        void rebuildMerchantRevenue_replaysAllEvents() {
            DomainEvent e = new DomainEvent();
            e.setEventType(DomainEventTypes.SUBSCRIPTION_ACTIVATED);
            when(domainEventRepository.findAll()).thenReturn(List.of(e));
            when(merchantRevenueRepo.count()).thenReturn(1L);

            var result = rebuildService.rebuildMerchantRevenueProjection();

            verify(merchantRevenueRepo).deleteAllInBatch();
            verify(projectionUpdateService).applyEventToMerchantRevenue(e);
            assertThat(result.getProjectionName()).isEqualTo("merchant_revenue");
        }
    }

    // =========================================================================
    // ProjectionLagMonitor — stale detection
    // =========================================================================

    @Nested
    @DisplayName("ProjectionLagMonitor — stale detection")
    class LagMonitorTests {

        @Mock com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository customerBillingRepo;
        @Mock com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository       kpiRepo;
        @Mock com.firstclub.reporting.ops.repository.SubscriptionStatusProjectionRepository             subStatusRepo;
        @Mock com.firstclub.reporting.ops.repository.InvoiceSummaryProjectionRepository                 invoiceSummaryRepo;
        @Mock com.firstclub.reporting.ops.repository.PaymentSummaryProjectionRepository                 paymentSummaryRepo;
        @Mock com.firstclub.reporting.ops.repository.ReconDashboardProjectionRepository                 reconDashboardRepo;
        @Mock CustomerPaymentSummaryProjectionRepository                                                 customerPaymentRepo;
        @Mock LedgerBalanceProjectionRepository                                                          ledgerBalanceRepo;
        @Mock MerchantRevenueProjectionRepository                                                        merchantRevenueRepo;
        @InjectMocks ProjectionLagMonitor lagMonitor;

        @Test
        @DisplayName("lagMonitorDetectsStaleProjection — oldest row far in the past exceeds threshold")
        void lagMonitorDetectsStaleProjection() {
            // Simulate an oldest row updated 2 hours ago
            LocalDateTime twoHoursAgo = LocalDateTime.now().minusHours(2);
            when(customerPaymentRepo.findOldestUpdatedAt()).thenReturn(Optional.of(twoHoursAgo));

            ProjectionLagReport report = lagMonitor.getLag("customer_payment_summary");

            assertThat(report.getLagSeconds()).isGreaterThanOrEqualTo(7200L);

            // Threshold of 60 seconds → should be stale
            assertThat(report.isStale(60L)).isTrue();

            // Threshold of an entire day → should NOT be stale
            assertThat(report.isStale(86400L)).isFalse();
        }

        @Test
        @DisplayName("empty projection table returns lagSeconds == -1 and isStale is false")
        void emptyTable_returnsNegativeLag() {
            when(customerPaymentRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());

            ProjectionLagReport report = lagMonitor.getLag("customer_payment_summary");

            assertThat(report.getLagSeconds()).isEqualTo(-1L);
            assertThat(report.isStale(0L)).isFalse();
        }

        @Test
        @DisplayName("freshly updated projection is not stale under normal threshold")
        void freshProjection_isNotStale() {
            LocalDateTime justNow = LocalDateTime.now().minusSeconds(5);
            when(customerPaymentRepo.findOldestUpdatedAt()).thenReturn(Optional.of(justNow));

            ProjectionLagReport report = lagMonitor.getLag("customer_payment_summary");

            // 5-second lag should not exceed a 60-second threshold
            assertThat(report.isStale(60L)).isFalse();
        }

        @Test
        @DisplayName("checkAllProjections returns map with all 9 projection names")
        void checkAllProjections_returnsNineEntries() {
            when(customerBillingRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(kpiRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(subStatusRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(invoiceSummaryRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(paymentSummaryRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(reconDashboardRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(customerPaymentRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(ledgerBalanceRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());
            when(merchantRevenueRepo.findOldestUpdatedAt()).thenReturn(Optional.empty());

            var all = lagMonitor.checkAllProjections();

            assertThat(all).hasSize(9)
                    .containsKey("customer_payment_summary")
                    .containsKey("ledger_balance")
                    .containsKey("merchant_revenue");
        }
    }

    // =========================================================================
    // ProjectionConsistencyChecker — mismatch detection
    // =========================================================================

    @Nested
    @DisplayName("ProjectionConsistencyChecker — mismatch detection")
    class ConsistencyCheckerTests {

        @Mock CustomerPaymentSummaryProjectionRepository customerPaymentRepo;
        @Mock MerchantRevenueProjectionRepository        merchantRevenueRepo;
        @Mock PaymentIntentV2Repository                  paymentIntentRepo;
        @Mock SubscriptionV2Repository                   subscriptionRepo;
        @InjectMocks ProjectionConsistencyChecker consistencyChecker;

        @Test
        @DisplayName("consistencyCheckerFindsMismatch — projection shows 3, source has 5 → inconsistent")
        void consistencyCheckerFindsMismatch() {
            Long merchantId = 10L;
            Long customerId = 20L;

            CustomerPaymentSummaryProjection staleProj = CustomerPaymentSummaryProjection.builder()
                    .merchantId(merchantId)
                    .customerId(customerId)
                    .successfulPayments(3)   // stale — source has 5
                    .build();

            PaymentIntentV2 pi1 = new PaymentIntentV2();
            PaymentIntentV2 pi2 = new PaymentIntentV2();
            PaymentIntentV2 pi3 = new PaymentIntentV2();
            PaymentIntentV2 pi4 = new PaymentIntentV2();
            PaymentIntentV2 pi5 = new PaymentIntentV2();

            when(customerPaymentRepo.findByMerchantIdAndCustomerId(merchantId, customerId))
                    .thenReturn(Optional.of(staleProj));
            when(paymentIntentRepo.findByMerchantIdAndCustomerIdAndStatus(
                    merchantId, customerId, PaymentIntentStatusV2.SUCCEEDED))
                    .thenReturn(List.of(pi1, pi2, pi3, pi4, pi5));

            ConsistencyReport report = consistencyChecker.checkCustomerPaymentSummary(merchantId, customerId);

            assertThat(report.isConsistent()).isFalse();
            assertThat(report.getProjectionValue()).isEqualTo(3L);
            assertThat(report.getSourceValue()).isEqualTo(5L);
            assertThat(report.getDelta()).isEqualTo(2L);
            assertThat(report.getCheckedField()).isEqualTo("successfulPayments");
        }

        @Test
        @DisplayName("when projection matches source, report is consistent with delta 0")
        void consistencyChecker_matchingValues_isConsistent() {
            Long merchantId = 11L;
            Long customerId = 21L;

            CustomerPaymentSummaryProjection freshProj = CustomerPaymentSummaryProjection.builder()
                    .merchantId(merchantId)
                    .customerId(customerId)
                    .successfulPayments(2)
                    .build();

            when(customerPaymentRepo.findByMerchantIdAndCustomerId(merchantId, customerId))
                    .thenReturn(Optional.of(freshProj));
            when(paymentIntentRepo.findByMerchantIdAndCustomerIdAndStatus(
                    merchantId, customerId, PaymentIntentStatusV2.SUCCEEDED))
                    .thenReturn(List.of(new PaymentIntentV2(), new PaymentIntentV2()));

            ConsistencyReport report = consistencyChecker.checkCustomerPaymentSummary(merchantId, customerId);

            assertThat(report.isConsistent()).isTrue();
            assertThat(report.getDelta()).isEqualTo(0L);
        }

        @Test
        @DisplayName("missing projection row is treated as zero — reports mismatch when source has data")
        void consistencyChecker_missingProjectionRow_treatedAsZero() {
            Long merchantId = 12L;
            Long customerId = 22L;

            when(customerPaymentRepo.findByMerchantIdAndCustomerId(merchantId, customerId))
                    .thenReturn(Optional.empty());
            when(paymentIntentRepo.findByMerchantIdAndCustomerIdAndStatus(
                    merchantId, customerId, PaymentIntentStatusV2.SUCCEEDED))
                    .thenReturn(List.of(new PaymentIntentV2()));

            ConsistencyReport report = consistencyChecker.checkCustomerPaymentSummary(merchantId, customerId);

            assertThat(report.isConsistent()).isFalse();
            assertThat(report.getProjectionValue()).isEqualTo(0L);
            assertThat(report.getSourceValue()).isEqualTo(1L);
        }

        @Test
        @DisplayName("checkMerchantRevenue — projection shows 5 active, source has 8 → inconsistent")
        void checkMerchantRevenue_mismatchDetected() {
            Long merchantId = 30L;

            MerchantRevenueProjection staleProj = MerchantRevenueProjection.builder()
                    .merchantId(merchantId)
                    .activeSubscriptions(5)
                    .build();

            when(merchantRevenueRepo.findById(merchantId)).thenReturn(Optional.of(staleProj));
            when(subscriptionRepo.findByMerchantIdAndStatus(eq(merchantId), eq(SubscriptionStatusV2.ACTIVE), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 8L));

            ConsistencyReport report = consistencyChecker.checkMerchantRevenue(merchantId);

            assertThat(report.isConsistent()).isFalse();
            assertThat(report.getProjectionValue()).isEqualTo(5L);
            assertThat(report.getSourceValue()).isEqualTo(8L);
            assertThat(report.getDelta()).isEqualTo(3L);
        }
    }

    // =========================================================================
    // ProjectionLagReport — isStale edge cases
    // =========================================================================

    @Nested
    @DisplayName("ProjectionLagReport — isStale edge cases")
    class LagReportTests {

        @Test
        @DisplayName("lagSeconds == -1 (empty table) is never stale regardless of threshold")
        void emptyTable_neverStale() {
            ProjectionLagReport report = ProjectionLagReport.builder()
                    .projectionName("test")
                    .lagSeconds(-1L)
                    .build();
            assertThat(report.isStale(0L)).isFalse();
            assertThat(report.isStale(1L)).isFalse();
        }

        @Test
        @DisplayName("lagSeconds exactly equals threshold is NOT stale (must exceed)")
        void lagEqualsThreshold_notStale() {
            ProjectionLagReport report = ProjectionLagReport.builder()
                    .projectionName("test")
                    .lagSeconds(60L)
                    .build();
            assertThat(report.isStale(60L)).isFalse();
        }

        @Test
        @DisplayName("lagSeconds exceeds threshold is stale")
        void lagExceedsThreshold_isStale() {
            ProjectionLagReport report = ProjectionLagReport.builder()
                    .projectionName("test")
                    .lagSeconds(61L)
                    .build();
            assertThat(report.isStale(60L)).isTrue();
        }
    }
}
