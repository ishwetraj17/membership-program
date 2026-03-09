package com.firstclub.events.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.events.dto.EventMetadataDTO;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DomainEventLog}.
 *
 * <p>Uses Mockito only — no Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DomainEventLog — Unit Tests")
class DomainEventLogTest {

    @Mock
    private DomainEventRepository domainEventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private DomainEventLog domainEventLog;

    @BeforeEach
    void clearMDC() {
        MDC.clear();
    }

    @AfterEach
    void clearMDCAfter() {
        MDC.clear();
    }

    // ── record(eventType, String) — no metadata ───────────────────────────────

    @Test
    @DisplayName("record(type, json) — saves event with default versions 1/1")
    void record_noMetadata_savesEventWithDefaultVersions() {
        DomainEvent persisted = DomainEvent.builder()
                .id(1L).eventType("INVOICE_CREATED").payload("{}")
                .eventVersion(1).schemaVersion(1).build();
        when(domainEventRepository.save(any())).thenReturn(persisted);

        DomainEvent result = domainEventLog.record("INVOICE_CREATED", "{}");

        assertThat(result.getEventType()).isEqualTo("INVOICE_CREATED");
        assertThat(result.getEventVersion()).isEqualTo(1);
        assertThat(result.getSchemaVersion()).isEqualTo(1);

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(domainEventRepository).save(captor.capture());
        DomainEvent saved = captor.getValue();
        assertThat(saved.getPayload()).isEqualTo("{}");
        assertThat(saved.getCorrelationId()).isNull(); // no MDC set
    }

    // ── MDC correlationId auto-pickup ────────────────────────────────────────

    @Test
    @DisplayName("record(type, json) — uses MDC requestId as correlationId when no metadata")
    void record_withMDCCorrelationId_usesItWhenNoMetadata() {
        MDC.put("requestId", "req-abc-123");
        when(domainEventRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        domainEventLog.record("PAYMENT_SUCCEEDED", "{\"invoiceId\":42}");

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(domainEventRepository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("req-abc-123");
    }

    // ── record(type, json, metadata) ─────────────────────────────────────────

    @Test
    @DisplayName("record(type, json, metadata) — propagates all metadata fields")
    void record_withMetadata_propagatesAllFields() {
        when(domainEventRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        EventMetadataDTO meta = EventMetadataDTO.builder()
                .eventVersion(2)
                .schemaVersion(3)
                .correlationId("corr-xyz")
                .causationId("cause-001")
                .aggregateType("Subscription")
                .aggregateId("sub-99")
                .merchantId(7L)
                .build();

        domainEventLog.record("SUBSCRIPTION_ACTIVATED", "{}", meta);

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(domainEventRepository).save(captor.capture());
        DomainEvent saved = captor.getValue();
        assertThat(saved.getEventVersion()).isEqualTo(2);
        assertThat(saved.getSchemaVersion()).isEqualTo(3);
        assertThat(saved.getCorrelationId()).isEqualTo("corr-xyz");
        assertThat(saved.getCausationId()).isEqualTo("cause-001");
        assertThat(saved.getAggregateType()).isEqualTo("Subscription");
        assertThat(saved.getAggregateId()).isEqualTo("sub-99");
        assertThat(saved.getMerchantId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("record with metadata where correlationId is null falls back to MDC requestId")
    void record_metadataWithNoCorrelationId_fallsBackToMDC() {
        MDC.put("requestId", "mdc-fallback-id");
        when(domainEventRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        EventMetadataDTO meta = EventMetadataDTO.builder()
                .aggregateType("Invoice")
                .aggregateId("inv-5")
                .build(); // correlationId is null

        domainEventLog.record("INVOICE_CREATED", "{}", meta);

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(domainEventRepository).save(captor.capture());
        // Should have fallen back to MDC requestId
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("mdc-fallback-id");
    }

    @Test
    @DisplayName("record with metadata having explicit correlationId — MDC is NOT used")
    void record_metadataWithExplicitCorrelationId_usesThat() {
        MDC.put("requestId", "mdc-should-not-be-used");
        when(domainEventRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        EventMetadataDTO meta = EventMetadataDTO.builder()
                .correlationId("explicit-corr")
                .build();

        domainEventLog.record("INVOICE_CREATED", "{}", meta);

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(domainEventRepository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("explicit-corr");
    }

    // ── record(type, Map) overload ───────────────────────────────────────────

    @Test
    @DisplayName("record(type, map) — serialises map to JSON before saving")
    void record_mapPayload_serialisedToJson() {
        when(domainEventRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        Map<String, Object> payload = Map.of("invoiceId", 10L, "amount", "99.00");
        domainEventLog.record("INVOICE_CREATED", payload);

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(domainEventRepository).save(captor.capture());
        String json = captor.getValue().getPayload();
        assertThat(json).contains("invoiceId");
        assertThat(json).contains("10");
    }

    @Test
    @DisplayName("record(type, map) — unserializable map throws IllegalStateException")
    void record_mapPayload_unserializable_throwsIllegalState() {
        // Inject a broken ObjectMapper that always fails
        ObjectMapper broken = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                throw new com.fasterxml.jackson.core.JsonGenerationException("forced failure", (com.fasterxml.jackson.core.JsonGenerator) null);
            }
        };
        DomainEventLog logWithBrokenMapper = new DomainEventLog(
                domainEventRepository, broken, eventPublisher);

        assertThatThrownBy(() ->
                logWithBrokenMapper.record("INVOICE_CREATED", Map.of("key", "value"))
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("serialisation failure");
    }
}
