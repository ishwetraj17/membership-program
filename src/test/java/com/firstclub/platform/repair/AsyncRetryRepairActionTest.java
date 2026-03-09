package com.firstclub.platform.repair;

import com.firstclub.outbox.entity.OutboxEvent;
import com.firstclub.outbox.repository.OutboxEventRepository;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDelivery;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.platform.repair.actions.OutboxEventRetryAction;
import com.firstclub.platform.repair.actions.WebhookDeliveryRetryAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for async-retry repair actions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Async Retry Repair Actions — Unit Tests")
class AsyncRetryRepairActionTest {

    // ── OutboxEventRetryAction ────────────────────────────────────────────────

    @Nested
    @DisplayName("OutboxEventRetryAction")
    class OutboxEventRetryTests {

        @Mock private OutboxEventRepository outboxEventRepository;
        @Mock private ObjectMapper          objectMapper;
        @InjectMocks private OutboxEventRetryAction action;

        @Test
        @DisplayName("metadata is correct")
        void metadata() {
            assertThat(action.getRepairKey()).isEqualTo("repair.outbox.retry_event");
            assertThat(action.getTargetType()).isEqualTo("OUTBOX_EVENT");
            assertThat(action.supportsDryRun()).isFalse();
        }

        @Test
        @DisplayName("EXECUTE — resets FAILED event to NEW")
        void execute_resetsToNew() throws Exception {
            OutboxEvent event = failedOutboxEvent(7L);
            when(outboxEventRepository.findById(7L)).thenReturn(Optional.of(event));
            when(outboxEventRepository.save(any())).thenReturn(event);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "7", Map.of(), false, 1L, "manual retry");
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            assertThat(event.getStatus()).isEqualTo(OutboxEvent.OutboxEventStatus.NEW);
            assertThat(event.getLastError()).isNull();
            verify(outboxEventRepository).save(event);
        }

        @Test
        @DisplayName("EXECUTE — throws when event not found")
        void execute_throwsWhenNotFound() {
            when(outboxEventRepository.findById(99L)).thenReturn(Optional.empty());
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "99", Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OutboxEvent not found");
        }

        @Test
        @DisplayName("EXECUTE — audit row contains previous status in details")
        void execute_detailsContainPreviousStatus() throws Exception {
            OutboxEvent event = failedOutboxEvent(3L);
            when(outboxEventRepository.findById(3L)).thenReturn(Optional.of(event));
            when(outboxEventRepository.save(any())).thenReturn(event);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "3", Map.of(), false, null, null);
            RepairActionResult result = action.execute(ctx);

            assertThat(result.getDetails()).contains("FAILED");
        }
    }

    // ── WebhookDeliveryRetryAction ────────────────────────────────────────────

    @Nested
    @DisplayName("WebhookDeliveryRetryAction")
    class WebhookDeliveryRetryTests {

        @Mock private MerchantWebhookDeliveryRepository deliveryRepository;
        @Mock private ObjectMapper                       objectMapper;
        @InjectMocks private WebhookDeliveryRetryAction action;

        @Test
        @DisplayName("metadata is correct")
        void metadata() {
            assertThat(action.getRepairKey()).isEqualTo("repair.webhook.retry_delivery");
            assertThat(action.getTargetType()).isEqualTo("WEBHOOK_DELIVERY");
            assertThat(action.supportsDryRun()).isFalse();
        }

        @Test
        @DisplayName("EXECUTE — resets GAVE_UP delivery to PENDING")
        void execute_resetsGaveUpToPending() throws Exception {
            MerchantWebhookDelivery delivery = gaveUpDelivery(15L);
            when(deliveryRepository.findById(15L)).thenReturn(Optional.of(delivery));
            when(deliveryRepository.save(any())).thenReturn(delivery);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "15", Map.of(), false, 1L, "retry after endpoint fix");
            RepairActionResult result = action.execute(ctx);

            assertThat(result.isSuccess()).isTrue();
            assertThat(delivery.getStatus()).isEqualTo(MerchantWebhookDeliveryStatus.PENDING);
            assertThat(delivery.getLastError()).isNull();
            verify(deliveryRepository).save(delivery);
        }

        @Test
        @DisplayName("EXECUTE — throws when delivery not found")
        void execute_throwsWhenNotFound() {
            when(deliveryRepository.findById(200L)).thenReturn(Optional.empty());
            RepairAction.RepairContext ctx = new RepairAction.RepairContext(
                    "200", Map.of(), false, null, null);
            assertThatThrownBy(() -> action.execute(ctx))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("WebhookDelivery not found");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    static OutboxEvent failedOutboxEvent(Long id) {
        OutboxEvent ev = new OutboxEvent();
        ev.setId(id);
        ev.setEventType("PAYMENT_SUCCEEDED");
        ev.setStatus(OutboxEvent.OutboxEventStatus.FAILED);
        ev.setAttempts(5);
        ev.setLastError("timeout");
        return ev;
    }

    static MerchantWebhookDelivery gaveUpDelivery(Long id) {
        MerchantWebhookDelivery d = new MerchantWebhookDelivery();
        d.setId(id);
        d.setEndpointId(3L);
        d.setEventType("SUBSCRIPTION_ACTIVATED");
        d.setStatus(MerchantWebhookDeliveryStatus.GAVE_UP);
        d.setAttemptCount(6);
        d.setLastError("connection refused");
        return d;
    }
}
