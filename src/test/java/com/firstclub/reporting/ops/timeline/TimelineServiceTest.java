package com.firstclub.reporting.ops.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.service.DomainEventTypes;
import com.firstclub.reporting.ops.timeline.cache.TimelineCacheService;
import com.firstclub.reporting.ops.timeline.dto.TimelineEventDTO;
import com.firstclub.reporting.ops.timeline.entity.TimelineEntityTypes;
import com.firstclub.reporting.ops.timeline.entity.TimelineEvent;
import com.firstclub.reporting.ops.timeline.repository.TimelineEventRepository;
import com.firstclub.reporting.ops.timeline.service.TimelineMapper;
import com.firstclub.reporting.ops.timeline.service.TimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TimelineMapper} and {@link TimelineService}.
 * No Spring context — pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Timeline — Mapper & Service Unit Tests")
class TimelineServiceTest {

    // ── Mapper Tests ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TimelineMapper")
    class MapperTests {

        @Spy
        ObjectMapper objectMapper = new ObjectMapper();

        @InjectMocks
        TimelineMapper mapper;

        // ── Helpers ───────────────────────────────────────────────────────────

        private DomainEvent event(String type, String payload) {
            return DomainEvent.builder()
                    .id(99L)
                    .eventType(type)
                    .merchantId(1L)
                    .payload(payload)
                    .createdAt(LocalDateTime.of(2025, 6, 1, 12, 0))
                    .build();
        }

        private TimelineEvent firstRowOfType(List<TimelineEvent> rows, String entityType) {
            return rows.stream()
                    .filter(r -> entityType.equals(r.getEntityType()))
                    .findFirst()
                    .orElse(null);
        }

        // ── Subscription lifecycle ─────────────────────────────────────────────

        @Test
        @DisplayName("SUBSCRIPTION_ACTIVATED with customerId \u2192 2 rows (SUBSCRIPTION + CUSTOMER)")
        void subscriptionActivated_emitsSubscriptionAndCustomerRows() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.SUBSCRIPTION_ACTIVATED,
                          "{\"subscriptionId\":10,\"customerId\":20}"));

            assertThat(rows).hasSize(2);
            TimelineEvent subRow = firstRowOfType(rows, TimelineEntityTypes.SUBSCRIPTION);
            assertThat(subRow).isNotNull();
            assertThat(subRow.getEntityId()).isEqualTo(10L);
            assertThat(subRow.getTitle()).isEqualTo("Subscription activated");
            assertThat(subRow.getSourceEventId()).isEqualTo(99L);

            TimelineEvent cusRow = firstRowOfType(rows, TimelineEntityTypes.CUSTOMER);
            assertThat(cusRow).isNotNull();
            assertThat(cusRow.getEntityId()).isEqualTo(20L);
        }

        @Test
        @DisplayName("SUBSCRIPTION_ACTIVATED without customerId \u2192 1 row (SUBSCRIPTION only)")
        void subscriptionActivated_withoutCustomerId_emitsOneRow() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.SUBSCRIPTION_ACTIVATED,
                          "{\"subscriptionId\":10}"));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getEntityType()).isEqualTo(TimelineEntityTypes.SUBSCRIPTION);
        }

        @Test
        @DisplayName("SUBSCRIPTION_CREATED \u2192 2 rows")
        void subscriptionCreated_emitsTwoRows() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.SUBSCRIPTION_CREATED,
                          "{\"subscriptionId\":5,\"customerId\":9}"));
            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("SUBSCRIPTION_CANCELLED \u2192 2 rows with 'Subscription cancelled' title")
        void subscriptionCancelled_emitsTwoRows() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.SUBSCRIPTION_CANCELLED,
                          "{\"subscriptionId\":5,\"customerId\":9}"));
            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(r -> "Subscription cancelled".equals(r.getTitle()));
        }

        @Test
        @DisplayName("SUBSCRIPTION_SUSPENDED (no customerId) \u2192 1 row")
        void subscriptionSuspended_oneRow() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.SUBSCRIPTION_SUSPENDED,
                          "{\"subscriptionId\":5}"));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTitle()).isEqualTo("Subscription suspended");
        }

        // ── Invoice ───────────────────────────────────────────────────────────

        @Test
        @DisplayName("INVOICE_CREATED \u2192 2 rows (INVOICE + SUBSCRIPTION)")
        void invoiceCreated_emitsTwoRows() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.INVOICE_CREATED,
                          "{\"invoiceId\":30,\"subscriptionId\":10}"));
            assertThat(rows).hasSize(2);
            assertThat(firstRowOfType(rows, TimelineEntityTypes.INVOICE)).isNotNull();
            assertThat(firstRowOfType(rows, TimelineEntityTypes.SUBSCRIPTION)).isNotNull();
        }

        @Test
        @DisplayName("INVOICE_CREATED without subscriptionId \u2192 1 row")
        void invoiceCreated_withoutSubscription_oneRow() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.INVOICE_CREATED, "{\"invoiceId\":30}"));
            assertThat(rows).hasSize(1);
        }

        @Test
        @DisplayName("INVOICE_CREATED without invoiceId \u2192 empty list")
        void invoiceCreated_missingInvoiceId_emitsEmptyList() {
            assertThat(mapper.map(
                    event(DomainEventTypes.INVOICE_CREATED,
                          "{\"subscriptionId\":10}"))).isEmpty();
        }

        // ── Payment ───────────────────────────────────────────────────────────

        @Test
        @DisplayName("PAYMENT_SUCCEEDED \u2192 2 rows (PAYMENT_INTENT + INVOICE)")
        void paymentSucceeded_emitsTwoRows() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.PAYMENT_SUCCEEDED,
                          "{\"paymentIntentId\":50,\"invoiceId\":30}"));
            assertThat(rows).hasSize(2);
            assertThat(firstRowOfType(rows, TimelineEntityTypes.PAYMENT_INTENT)).isNotNull();
            assertThat(firstRowOfType(rows, TimelineEntityTypes.INVOICE)).isNotNull();
        }

        @Test
        @DisplayName("PAYMENT_ATTEMPT_FAILED attaches failure summary")
        void paymentAttemptFailed_includesGatewayAndCategory() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.PAYMENT_ATTEMPT_FAILED,
                          "{\"paymentIntentId\":50,\"gatewayName\":\"stripe\",\"failureCategory\":\"INSUFFICIENT_FUNDS\"}"));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getSummary()).contains("stripe").contains("INSUFFICIENT_FUNDS");
        }

        @Test
        @DisplayName("PAYMENT_INTENT_CREATED \u2192 2 rows (PAYMENT_INTENT + INVOICE)")
        void paymentIntentCreated_emitsTwoRows() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.PAYMENT_INTENT_CREATED,
                          "{\"paymentIntentId\":50,\"invoiceId\":30}"));
            assertThat(rows).hasSize(2);
        }

        // ── Refund ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("REFUND_ISSUED uses refundId field")
        void refundIssued_usesRefundId() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.REFUND_ISSUED,
                          "{\"refundId\":7,\"paymentIntentId\":50}"));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getEntityType()).isEqualTo(TimelineEntityTypes.REFUND);
            assertThat(rows.get(0).getEntityId()).isEqualTo(7L);
            assertThat(rows.get(0).getRelatedEntityType()).isEqualTo(TimelineEntityTypes.PAYMENT_INTENT);
        }

        @Test
        @DisplayName("REFUND_ISSUED falls back to refundV2Id when refundId is absent")
        void refundIssued_fallsBackToRefundV2Id() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.REFUND_ISSUED,
                          "{\"refundV2Id\":11,\"paymentIntentId\":50}"));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getEntityId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("REFUND_COMPLETED emits REFUND row")
        void refundCompleted_emitsRefundRow() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.REFUND_COMPLETED,
                          "{\"refundId\":7}"));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTitle()).isEqualTo("Refund completed");
        }

        // ── Dispute ───────────────────────────────────────────────────────────

        @Test
        @DisplayName("DISPUTE_OPENED \u2192 2 rows (DISPUTE + CUSTOMER) when customerId present")
        void disputeOpened_emitsDisputeAndCustomerRows() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.DISPUTE_OPENED,
                          "{\"disputeId\":3,\"paymentId\":50,\"customerId\":20}"));
            assertThat(rows).hasSize(2);
            assertThat(firstRowOfType(rows, TimelineEntityTypes.DISPUTE)).isNotNull();
            assertThat(firstRowOfType(rows, TimelineEntityTypes.CUSTOMER)).isNotNull();
        }

        @Test
        @DisplayName("DISPUTE_OPENED without customerId \u2192 1 row")
        void disputeOpened_withoutCustomer_oneRow() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.DISPUTE_OPENED,
                          "{\"disputeId\":3,\"paymentId\":50}"));
            assertThat(rows).hasSize(1);
        }

        // ── Risk ──────────────────────────────────────────────────────────────

        @Test
        @DisplayName("RISK_DECISION_MADE includes decision in title")
        void riskDecisionMade_titleContainsDecision() {
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.RISK_DECISION_MADE,
                          "{\"paymentIntentId\":50,\"decision\":\"BLOCK\"}"));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getTitle()).isEqualTo("Risk decision: BLOCK");
        }

        // ── Skipped / edge-case events ────────────────────────────────────────

        @Test
        @DisplayName("RECON_COMPLETED → empty list (not entity-scoped)")
        void reconCompleted_emitsEmptyList() {
            assertThat(mapper.map(
                    event(DomainEventTypes.RECON_COMPLETED, "{}"))).isEmpty();
        }

        @Test
        @DisplayName("SETTLEMENT_COMPLETED → empty list")
        void settlementCompleted_emitsEmptyList() {
            assertThat(mapper.map(
                    event(DomainEventTypes.SETTLEMENT_COMPLETED, "{}"))).isEmpty();
        }

        @Test
        @DisplayName("Unknown event type → empty list")
        void unknownEventType_emitsEmptyList() {
            assertThat(mapper.map(event("MYSTERY_EVENT", "{}"))).isEmpty();
        }

        @Test
        @DisplayName("Null merchantId \u2192 empty list")
        void nullMerchantId_emitsEmptyList() {
            DomainEvent de = DomainEvent.builder()
                    .id(1L)
                    .eventType(DomainEventTypes.SUBSCRIPTION_ACTIVATED)
                    .merchantId(null)
                    .payload("{\"subscriptionId\":1}")
                    .createdAt(LocalDateTime.now())
                    .build();
            assertThat(mapper.map(de)).isEmpty();
        }

        @Test
        @DisplayName("Malformed JSON payload \u2192 empty list (silent skip)")
        void malformedPayload_emitsEmptyList() {
            assertThat(mapper.map(
                    event(DomainEventTypes.INVOICE_CREATED, "{not-json}"))).isEmpty();
        }

        // ── Metadata propagation ──────────────────────────────────────────────

        @Test
        @DisplayName("correlationId and causationId propagate to all emitted rows")
        void correlationAndCausationIds_propagatedToAllRows() {
            DomainEvent de = DomainEvent.builder()
                    .id(5L)
                    .eventType(DomainEventTypes.SUBSCRIPTION_ACTIVATED)
                    .merchantId(1L)
                    .payload("{\"subscriptionId\":10,\"customerId\":20}")
                    .correlationId("corr-abc")
                    .causationId("caus-xyz")
                    .createdAt(LocalDateTime.now())
                    .build();

            List<TimelineEvent> rows = mapper.map(de);
            assertThat(rows).hasSize(2);
            assertThat(rows).allMatch(r -> "corr-abc".equals(r.getCorrelationId()));
            assertThat(rows).allMatch(r -> "caus-xyz".equals(r.getCausationId()));
            assertThat(rows).allMatch(r -> Long.valueOf(5L).equals(r.getSourceEventId()));
        }

        @Test
        @DisplayName("payloadPreviewJson truncated to 500 chars")
        void payloadPreviewJson_truncatedTo500Chars() {
            String bigPayload = "{\"invoiceId\":1,\"data\":\"" + "x".repeat(600) + "\"}";
            List<TimelineEvent> rows = mapper.map(
                    event(DomainEventTypes.INVOICE_CREATED, bigPayload));
            assertThat(rows).isNotEmpty();
            rows.forEach(r ->
                    assertThat(r.getPayloadPreviewJson()).hasSizeLessThanOrEqualTo(500));
        }
    }

    // ── Service Tests ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TimelineService")
    class ServiceTests {

        @Mock private TimelineEventRepository repo;
        @Mock private TimelineMapper          mapper;
        @Mock private TimelineCacheService    cache;

        @InjectMocks
        private TimelineService service;

        // ── Helpers ───────────────────────────────────────────────────────────

        private DomainEvent domainEvent() {
            return DomainEvent.builder()
                    .id(99L).eventType("SUBSCRIPTION_ACTIVATED").merchantId(1L)
                    .payload("{}").createdAt(LocalDateTime.now()).build();
        }

        private TimelineEvent timelineRow(Long sourceEventId, String entityType, Long entityId) {
            return TimelineEvent.builder()
                    .merchantId(1L)
                    .entityType(entityType)
                    .entityId(entityId)
                    .eventType("SUBSCRIPTION_ACTIVATED")
                    .eventTime(LocalDateTime.now())
                    .title("Subscription activated")
                    .sourceEventId(sourceEventId)
                    .build();
        }

        // ── appendFromEvent ───────────────────────────────────────────────────

        @Test
        @DisplayName("appendFromEvent — saves all rows when none exist in DB")
        void appendFromEvent_savesAllNewRows() {
            TimelineEvent r1 = timelineRow(99L, "SUBSCRIPTION", 10L);
            TimelineEvent r2 = timelineRow(99L, "CUSTOMER", 20L);
            when(mapper.map(any())).thenReturn(List.of(r1, r2));
            when(repo.existsDedup(anyLong(), anyString(), anyLong())).thenReturn(false);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.appendFromEvent(domainEvent());

            verify(repo, times(2)).save(any(TimelineEvent.class));
            verify(cache, times(2)).evict(anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("appendFromEvent — skips row that already exists (dedup)")
        void appendFromEvent_skipsExistingRow() {
            TimelineEvent r1 = timelineRow(99L, "SUBSCRIPTION", 10L);
            TimelineEvent r2 = timelineRow(99L, "CUSTOMER", 20L);
            when(mapper.map(any())).thenReturn(List.of(r1, r2));
            // first row already exists, second is new
            when(repo.existsDedup(99L, "SUBSCRIPTION", 10L)).thenReturn(true);
            when(repo.existsDedup(99L, "CUSTOMER", 20L)).thenReturn(false);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.appendFromEvent(domainEvent());

            verify(repo, times(1)).save(any(TimelineEvent.class));
            verify(cache, times(1)).evict(anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("appendFromEvent — no-op when mapper returns empty list")
        void appendFromEvent_noOpOnEmptyMapperResult() {
            when(mapper.map(any())).thenReturn(List.of());
            service.appendFromEvent(domainEvent());

            verify(repo, never()).existsDedup(anyLong(), anyString(), anyLong());
            verify(repo, never()).save(any());
            verify(cache, never()).evict(anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("appendFromEvent — row with null sourceEventId skips dedup check and is saved")
        void appendFromEvent_nullSourceEventId_savedWithoutDedupCheck() {
            TimelineEvent row = TimelineEvent.builder()
                    .merchantId(1L).entityType("SUBSCRIPTION").entityId(10L)
                    .eventType("MANUAL").eventTime(LocalDateTime.now())
                    .title("Manual entry").sourceEventId(null).build();
            when(mapper.map(any())).thenReturn(List.of(row));
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

            service.appendFromEvent(domainEvent());

            verify(repo, never()).existsDedup(any(), anyString(), anyLong());
            verify(repo, times(1)).save(any());
        }

        // ── getTimelineForEntity ──────────────────────────────────────────────

        @Test
        @DisplayName("getTimelineForEntity — returns cached list without DB call on cache hit")
        void getTimelineForEntity_cacheHit_noDatabaseCall() {
            List<TimelineEventDTO> cached = List.of(
                    TimelineEventDTO.builder().id(1L).title("Cached event").build());
            when(cache.get(1L, "SUBSCRIPTION", 10L)).thenReturn(Optional.of(cached));

            List<TimelineEventDTO> result = service.getTimelineForEntity(1L, "SUBSCRIPTION", 10L);

            assertThat(result).isSameAs(cached);
            verify(repo, never()).findByMerchantIdAndEntityTypeAndEntityIdOrderByEventTimeDescIdDesc(
                    anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("getTimelineForEntity — queries DB on cache miss and populates cache")
        void getTimelineForEntity_cacheMiss_queriesDbAndCaches() {
            TimelineEvent entity = timelineRow(99L, "SUBSCRIPTION", 10L);
            entity.setId(1L);
            when(cache.get(1L, "SUBSCRIPTION", 10L)).thenReturn(Optional.empty());
            when(repo.findByMerchantIdAndEntityTypeAndEntityIdOrderByEventTimeDescIdDesc(1L, "SUBSCRIPTION", 10L))
                    .thenReturn(List.of(entity));

            List<TimelineEventDTO> result = service.getTimelineForEntity(1L, "SUBSCRIPTION", 10L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEntityType()).isEqualTo("SUBSCRIPTION");
            verify(cache).put(eq(1L), eq("SUBSCRIPTION"), eq(10L), anyList());
        }

        // ── getTimelineByCorrelation ──────────────────────────────────────────

        @Test
        @DisplayName("getTimelineByCorrelation — delegates to repository with merchantId filter")
        void getTimelineByCorrelation_queriesRepository() {
            Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "eventTime"));
            TimelineEvent entity = timelineRow(99L, "SUBSCRIPTION", 10L);
            entity.setId(2L);
            Page<TimelineEvent> page = new PageImpl<>(List.of(entity), pageable, 1);
            when(repo.findByMerchantIdAndCorrelationIdOrderByEventTimeDescIdDesc(1L, "corr-xyz", pageable))
                    .thenReturn(page);

            Page<TimelineEventDTO> result = service.getTimelineByCorrelation(1L, "corr-xyz", pageable);

            assertThat(result.getContent()).hasSize(1);
            verify(repo).findByMerchantIdAndCorrelationIdOrderByEventTimeDescIdDesc(1L, "corr-xyz", pageable);
        }
    }
}
