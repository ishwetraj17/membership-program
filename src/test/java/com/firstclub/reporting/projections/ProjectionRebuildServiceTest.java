package com.firstclub.reporting.projections;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.reporting.projections.dto.RebuildResponseDTO;
import com.firstclub.reporting.projections.repository.CustomerBillingSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantDailyKpiProjectionRepository;
import com.firstclub.reporting.projections.service.ProjectionRebuildService;
import com.firstclub.reporting.projections.service.ProjectionUpdateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProjectionRebuildService}.
 * No Spring context — pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectionRebuildService — Unit Tests")
class ProjectionRebuildServiceTest {

    @Mock private DomainEventRepository                      domainEventRepository;
    @Mock private CustomerBillingSummaryProjectionRepository billingSummaryRepo;
    @Mock private MerchantDailyKpiProjectionRepository       kpiRepo;
    @Mock private ProjectionUpdateService                    projectionUpdateService;

    @InjectMocks
    private ProjectionRebuildService service;

    private DomainEvent makeEvent(String type) {
        return DomainEvent.builder()
                .id(System.nanoTime())
                .eventType(type)
                .merchantId(1L)
                .payload("{\"customerId\": 10}")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── rebuildCustomerBillingSummaryProjection ──────────────────────────────

    @Test
    @DisplayName("rebuildCustomerBilling — calls deleteAllInBatch then applies all events")
    void rebuildCustomerBilling_deletesAllAndReprocessesEvents() {
        List<DomainEvent> events = List.of(
                makeEvent(DomainEventTypes.INVOICE_CREATED),
                makeEvent(DomainEventTypes.PAYMENT_SUCCEEDED)
        );
        when(domainEventRepository.findAll()).thenReturn(events);
        when(billingSummaryRepo.count()).thenReturn(1L);

        service.rebuildCustomerBillingSummaryProjection();

        InOrder order = inOrder(billingSummaryRepo, projectionUpdateService);
        order.verify(billingSummaryRepo).deleteAllInBatch();
        order.verify(projectionUpdateService, times(2))
                .applyEventToCustomerBillingProjection(any(DomainEvent.class));
    }

    @Test
    @DisplayName("rebuildCustomerBilling — returns correct stats")
    void rebuildCustomerBilling_returnsCorrectStats() {
        List<DomainEvent> events = List.of(
                makeEvent(DomainEventTypes.INVOICE_CREATED),
                makeEvent(DomainEventTypes.INVOICE_CREATED),
                makeEvent(DomainEventTypes.PAYMENT_SUCCEEDED)
        );
        when(domainEventRepository.findAll()).thenReturn(events);
        when(billingSummaryRepo.count()).thenReturn(2L);

        RebuildResponseDTO result = service.rebuildCustomerBillingSummaryProjection();

        assertThat(result.getProjectionName()).isEqualTo("customer_billing_summary");
        assertThat(result.getEventsProcessed()).isEqualTo(3);
        assertThat(result.getRecordsInProjection()).isEqualTo(2L);
        assertThat(result.getRebuiltAt()).isNotNull();
    }

    // ── rebuildMerchantDailyKpiProjection ─────────────────────────────────────

    @Test
    @DisplayName("rebuildMerchantDailyKpi — calls deleteAllInBatch then applies all events")
    void rebuildKpi_deletesAllAndReprocessesEvents() {
        List<DomainEvent> events = List.of(
                makeEvent(DomainEventTypes.PAYMENT_SUCCEEDED),
                makeEvent(DomainEventTypes.DISPUTE_OPENED)
        );
        when(domainEventRepository.findAll()).thenReturn(events);
        when(kpiRepo.count()).thenReturn(1L);

        service.rebuildMerchantDailyKpiProjection();

        InOrder order = inOrder(kpiRepo, projectionUpdateService);
        order.verify(kpiRepo).deleteAllInBatch();
        order.verify(projectionUpdateService, times(2))
                .applyEventToMerchantDailyKpi(any(DomainEvent.class));
    }

    @Test
    @DisplayName("rebuildMerchantDailyKpi — returns correct projection name and stats")
    void rebuildKpi_returnsCorrectStats() {
        when(domainEventRepository.findAll()).thenReturn(List.of(
                makeEvent(DomainEventTypes.INVOICE_CREATED)));
        when(kpiRepo.count()).thenReturn(1L);

        RebuildResponseDTO result = service.rebuildMerchantDailyKpiProjection();

        assertThat(result.getProjectionName()).isEqualTo("merchant_daily_kpi");
        assertThat(result.getEventsProcessed()).isEqualTo(1);
        assertThat(result.getRecordsInProjection()).isEqualTo(1L);
    }

    // ── SUPPORTED_PROJECTIONS constant ───────────────────────────────────────

    @Test
    @DisplayName("SUPPORTED_PROJECTIONS contains expected names")
    void supportedProjectionsContainsExpectedNames() {
        assertThat(ProjectionRebuildService.SUPPORTED_PROJECTIONS)
                .containsExactlyInAnyOrder(
                        "customer_billing_summary", "merchant_daily_kpi",
                        "subscription_status", "invoice_summary",
                        "payment_summary", "recon_dashboard",
                        "customer_payment_summary", "ledger_balance", "merchant_revenue");
    }
}
