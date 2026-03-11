package com.firstclub.reporting.projections.listener;

import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.event.DomainEventRecordedEvent;
import com.firstclub.reporting.ops.service.OpsProjectionUpdateService;
import com.firstclub.reporting.ops.timeline.service.TimelineService;
import com.firstclub.reporting.projections.service.ProjectionUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Asynchronous listener that keeps projection tables in near-real-time sync
 * with the domain event log.
 *
 * <p>Spring's {@code @Async} annotation causes this handler to run on a thread
 * from the async executor, decoupling projection updates from the originating
 * write transaction. The {@code @EnableAsync} annotation on
 * {@code MembershipApplication} activates this behaviour.
 *
 * <p>Failures here are logged but do not roll back the originating transaction.
 * Projections can always be fully rebuilt via
 * {@link com.firstclub.reporting.projections.service.ProjectionRebuildService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectionEventListener {

    private final ProjectionUpdateService    projectionUpdateService;
    private final OpsProjectionUpdateService  opsProjectionUpdateService;
    private final TimelineService             timelineService;

    @EventListener
    @Async
    public void onDomainEventRecorded(DomainEventRecordedEvent event) {
        DomainEvent de = event.getDomainEvent();
        log.debug("Async projection update triggered by event type={} id={}", de.getEventType(), de.getId());
        try {
            projectionUpdateService.applyEventToCustomerBillingProjection(de);
            projectionUpdateService.applyEventToMerchantDailyKpi(de);
            projectionUpdateService.applyEventToCustomerPaymentSummary(de);
            projectionUpdateService.applyEventToLedgerBalance(de);
            projectionUpdateService.applyEventToMerchantRevenue(de);
            opsProjectionUpdateService.applyEventToSubscriptionStatusProjection(de);
            opsProjectionUpdateService.applyEventToInvoiceSummaryProjection(de);
            opsProjectionUpdateService.applyEventToPaymentSummaryProjection(de);
            opsProjectionUpdateService.applyEventToReconDashboardProjection(de);
            timelineService.appendFromEvent(de);
        } catch (Exception ex) {
            log.error("Projection update failed for event type={} id={} — projections may be stale; "
                    + "run a rebuild to re-synchronise", de.getEventType(), de.getId(), ex);
        }
    }
}
