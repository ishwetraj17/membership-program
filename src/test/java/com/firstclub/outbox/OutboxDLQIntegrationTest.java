package com.firstclub.outbox;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.entity.OutboxEvent.OutboxEventStatus;
import com.firstclub.outbox.handler.OutboxEventHandler;
import com.firstclub.outbox.handler.OutboxEventHandlerRegistry;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.payments.entity.DeadLetterMessage;
import com.firstclub.payments.repository.DeadLetterMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Integration tests verifying that an always-failing handler routes to the
 * dead-letter table after {@link OutboxService#MAX_ATTEMPTS} retries.
 *
 * <h3>Scenario</h3>
 * An outbox event is seeded with status=NEW.  A mock handler always throws.
 * {@link OutboxService#processSingleEvent(Long)} is called {@code MAX_ATTEMPTS}
 * times.  After the final attempt the event must be FAILED and one
 * {@code dead_letter_messages} row with {@code source = 'OUTBOX'} must exist.
 */
class OutboxDLQIntegrationTest extends PostgresIntegrationTestBase {

    @MockitoBean
    private OutboxEventHandlerRegistry handlerRegistry;

    @Autowired private OutboxService           outboxService;
    @Autowired private OutboxEventRepository   outboxEventRepository;
    @Autowired private DeadLetterMessageRepository deadLetterRepository;

    private long dlqCountBefore;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        dlqCountBefore = deadLetterRepository.count();
    }

    @Test
    @DisplayName("always-failing handler → FAILED status + DLQ entry after MAX_ATTEMPTS")
    void alwaysFailingHandler_routesToDLQAfterMaxAttempts() {
        // Arrange: handler always throws
        OutboxEventHandler failingHandler = new OutboxEventHandler() {
            @Override public String getEventType() { return "BAD_EVENT"; }
            @Override public void handle(OutboxEvent event) throws Exception {
                throw new RuntimeException("simulated handler failure");
            }
        };
        when(handlerRegistry.resolve(any())).thenReturn(Optional.of(failingHandler));

        OutboxEvent event = outboxEventRepository.save(OutboxEvent.builder()
                .eventType("BAD_EVENT")
                .payload("{\"x\":1}")
                .status(OutboxEventStatus.NEW)
                .attempts(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(5))
                .build());
        Long eventId = event.getId();

        // Act: exhaust all retries
        for (int i = 0; i < OutboxService.MAX_ATTEMPTS; i++) {
            outboxService.processSingleEvent(eventId);
        }

        // Assert — event is permanently FAILED
        OutboxEvent finalEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(finalEvent.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(finalEvent.getAttempts()).isEqualTo(OutboxService.MAX_ATTEMPTS);
        assertThat(finalEvent.getLastError()).contains("simulated handler failure");

        // Assert — exactly one new DLQ entry with source=OUTBOX
        long dlqCountAfter = deadLetterRepository.count();
        assertThat(dlqCountAfter).isEqualTo(dlqCountBefore + 1);

        List<DeadLetterMessage> dlqEntries = deadLetterRepository.findAll().stream()
                .filter(m -> "OUTBOX".equals(m.getSource())
                        && m.getPayload().contains("BAD_EVENT"))
                .toList();
        assertThat(dlqEntries).hasSize(1);
        assertThat(dlqEntries.get(0).getError()).contains("simulated handler failure");
    }

    @Test
    @DisplayName("partial failures with eventual success — does not go to DLQ")
    void partialFailureThenSuccess_noDLQEntry() {
        // handler fails twice then succeeds on the third attempt
        final int[] callCount = {0};
        OutboxEventHandler flakyHandler = new OutboxEventHandler() {
            @Override public String getEventType() { return "FLAKY_EVENT"; }
            @Override public void handle(OutboxEvent event) throws Exception {
                callCount[0]++;
                if (callCount[0] < 3) {
                    throw new RuntimeException("transient error " + callCount[0]);
                }
                // 3rd call succeeds
            }
        };
        when(handlerRegistry.resolve(any())).thenReturn(Optional.of(flakyHandler));

        OutboxEvent event = outboxEventRepository.save(OutboxEvent.builder()
                .eventType("FLAKY_EVENT")
                .payload("{\"x\":2}")
                .status(OutboxEventStatus.NEW)
                .attempts(0)
                .nextAttemptAt(LocalDateTime.now().minusSeconds(5))
                .build());
        Long eventId = event.getId();

        // Call 1 + 2: fail → stays NEW with incremented attempts
        outboxService.processSingleEvent(eventId);
        outboxService.processSingleEvent(eventId);

        OutboxEvent afterTwoFailures = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(afterTwoFailures.getStatus()).isEqualTo(OutboxEventStatus.NEW);
        assertThat(afterTwoFailures.getAttempts()).isEqualTo(2);

        // Call 3: succeeds → DONE
        outboxService.processSingleEvent(eventId);

        OutboxEvent finalEvent = outboxEventRepository.findById(eventId).orElseThrow();
        assertThat(finalEvent.getStatus()).isEqualTo(OutboxEventStatus.DONE);

        // No new DLQ entries
        assertThat(deadLetterRepository.count()).isEqualTo(dlqCountBefore);
    }
}
