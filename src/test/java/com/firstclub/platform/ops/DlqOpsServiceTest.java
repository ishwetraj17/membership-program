package com.firstclub.platform.ops;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.payments.entity.DeadLetterMessage;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import com.firstclub.platform.ops.dto.DlqEntryResponseDTO;
import com.firstclub.platform.ops.service.impl.DlqOpsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DlqOpsService — Unit Tests")
class DlqOpsServiceTest {

    @Mock DeadLetterMessageRepository deadLetterMessageRepository;
    @Mock OutboxEventRepository       outboxEventRepository;
    @InjectMocks DlqOpsServiceImpl    service;
    @Captor ArgumentCaptor<OutboxEvent> eventCaptor;

    private DeadLetterMessage dlqEntry(Long id, String source, String payload) {
        DeadLetterMessage m = new DeadLetterMessage();
        m.setId(id);
        m.setSource(source);
        m.setPayload(payload);
        m.setError("processing failed");
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }

    @Nested
    @DisplayName("listDlqEntries")
    class ListDlqEntries {

        @Test
        @DisplayName("returns mapped DTOs for all DLQ entries when no filters")
        void returnsMappedDTOs() {
            when(deadLetterMessageRepository.findAll()).thenReturn(List.of(
                    dlqEntry(1L, "WEBHOOK", "{\"event\":\"payment\"}"),
                    dlqEntry(2L, "OUTBOX",  "{\"event\":\"invoice\"}")));

            List<DlqEntryResponseDTO> result = service.listDlqEntries(null, null);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).source()).isEqualTo("WEBHOOK");
            assertThat(result.get(1).source()).isEqualTo("OUTBOX");
        }

        @Test
        @DisplayName("returns empty list when DLQ is empty")
        void returnsEmpty_whenDlqEmpty() {
            when(deadLetterMessageRepository.findAll()).thenReturn(List.of());

            assertThat(service.listDlqEntries(null, null)).isEmpty();
        }

        @Test
        @DisplayName("filters by source when source param is provided")
        void filtersBySource() {
            when(deadLetterMessageRepository.findBySource("OUTBOX")).thenReturn(List.of(
                    dlqEntry(3L, "OUTBOX", "INVOICE_CREATED|{\"id\":1}")));

            List<DlqEntryResponseDTO> result = service.listDlqEntries("OUTBOX", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).source()).isEqualTo("OUTBOX");
            verify(deadLetterMessageRepository).findBySource("OUTBOX");
            verify(deadLetterMessageRepository, never()).findAll();
        }

        @Test
        @DisplayName("filters by failureCategory when failureCategory param is provided")
        void filtersByFailureCategory() {
            DeadLetterMessage entry = dlqEntry(4L, "OUTBOX", "PAYMENT_SUCCEEDED|{\"id\":2}");
            entry.setFailureCategory("TRANSIENT_ERROR");
            when(deadLetterMessageRepository.findByFailureCategory("TRANSIENT_ERROR"))
                    .thenReturn(List.of(entry));

            List<DlqEntryResponseDTO> result = service.listDlqEntries(null, "TRANSIENT_ERROR");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).failureCategory()).isEqualTo("TRANSIENT_ERROR");
        }

        @Test
        @DisplayName("filters by both source and failureCategory when both params are provided")
        void filtersBySourceAndFailureCategory() {
            when(deadLetterMessageRepository.findBySourceAndFailureCategory("OUTBOX", "PAYLOAD_PARSE_ERROR"))
                    .thenReturn(List.of());

            service.listDlqEntries("OUTBOX", "PAYLOAD_PARSE_ERROR");

            verify(deadLetterMessageRepository).findBySourceAndFailureCategory("OUTBOX", "PAYLOAD_PARSE_ERROR");
        }
    }

    @Nested
    @DisplayName("retryDlqEntry")
    class RetryDlqEntry {

        @Test
        @DisplayName("parses pipe-delimited payload: eventType from prefix, JSON payload from suffix")
        void parsesPipeDelimitedPayload_correctEventType() {
            // Payload stored by OutboxService.writeToDLQ: "{eventType}|{jsonPayload}"
            DeadLetterMessage dlq = dlqEntry(5L, "OUTBOX", "INVOICE_CREATED|{\"id\":99}");
            when(deadLetterMessageRepository.findById(5L)).thenReturn(Optional.of(dlq));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            DlqEntryResponseDTO result = service.retryDlqEntry(5L);

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();
            // eventType must be the real event type, NOT "OUTBOX"
            assertThat(saved.getEventType()).isEqualTo("INVOICE_CREATED");
            assertThat(saved.getPayload()).isEqualTo("{\"id\":99}");
            assertThat(saved.getStatus()).isEqualTo(OutboxEventStatus.NEW);
            assertThat(saved.getAttempts()).isZero();

            verify(deadLetterMessageRepository).delete(dlq);
            assertThat(result.id()).isEqualTo(5L);
        }

        @Test
        @DisplayName("falls back to source-as-eventType for legacy records without pipe delimiter")
        void fallsBack_whenNoPipeDelimiter() {
            DeadLetterMessage dlq = dlqEntry(6L, "PAYMENT_SUCCEEDED", "{\"id\":99}");
            when(deadLetterMessageRepository.findById(6L)).thenReturn(Optional.of(dlq));
            when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.retryDlqEntry(6L);

            verify(outboxEventRepository).save(eventCaptor.capture());
            OutboxEvent saved = eventCaptor.getValue();
            assertThat(saved.getEventType()).isEqualTo("PAYMENT_SUCCEEDED");
            assertThat(saved.getPayload()).isEqualTo("{\"id\":99}");
        }

        @Test
        @DisplayName("throws 404 when DLQ entry not found")
        void throws404_whenEntryNotFound() {
            when(deadLetterMessageRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.retryDlqEntry(999L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("999");
        }
    }
}
