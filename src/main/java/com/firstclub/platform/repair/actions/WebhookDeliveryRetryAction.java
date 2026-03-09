package com.firstclub.platform.repair.actions;

import com.firstclub.notifications.webhooks.entity.MerchantWebhookDelivery;
import com.firstclub.notifications.webhooks.entity.MerchantWebhookDeliveryStatus;
import com.firstclub.notifications.webhooks.repository.MerchantWebhookDeliveryRepository;
import com.firstclub.platform.repair.RepairAction;
import com.firstclub.platform.repair.RepairActionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resets a GAVE_UP or FAILED webhook delivery so the scheduler will retry it.
 *
 * <p><b>What changes:</b> {@code status → PENDING},
 * {@code next_attempt_at → now}, {@code last_error} cleared.
 *
 * <p><b>What is never changed:</b> {@code payload}, {@code signature},
 * {@code endpoint_id}, {@code event_type}, {@code attempt_count} (preserved).
 *
 * <p><b>Dry-run:</b> not supported.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryRetryAction implements RepairAction {

    private final MerchantWebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper                       objectMapper;

    @Override
    public String getRepairKey() { return "repair.webhook.retry_delivery"; }

    @Override
    public String getTargetType() { return "WEBHOOK_DELIVERY"; }

    @Override
    public boolean supportsDryRun() { return false; }

    @Override
    @Transactional
    public RepairActionResult execute(RepairContext context) {
        Long deliveryId = parseId(context.targetId());
        MerchantWebhookDelivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("WebhookDelivery not found: " + deliveryId));

        // Guard 1: do not reset a delivery that already succeeded
        if (delivery.getStatus() == MerchantWebhookDeliveryStatus.DELIVERED) {
            return RepairActionResult.builder()
                    .repairKey(getRepairKey()).success(false).dryRun(false)
                    .details("Delivery " + deliveryId + " is already DELIVERED — no retry needed")
                    .evaluatedAt(LocalDateTime.now()).build();
        }
        // Guard 2: do not reset a delivery that is currently in-flight
        if (delivery.getProcessingStartedAt() != null) {
            return RepairActionResult.builder()
                    .repairKey(getRepairKey()).success(false).dryRun(false)
                    .details("Delivery " + deliveryId + " is currently in-flight (owner="
                            + delivery.getProcessingOwner() + ") — refusing retry to prevent double-dispatch")
                    .evaluatedAt(LocalDateTime.now()).build();
        }

        String beforeJson = snapshot(delivery);
        MerchantWebhookDeliveryStatus previousStatus = delivery.getStatus();

        delivery.setStatus(MerchantWebhookDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(LocalDateTime.now());
        delivery.setLastError(null);
        deliveryRepository.save(delivery);

        String afterJson = snapshot(delivery);
        log.info("WebhookDeliveryRetryAction: delivery={} reset from {} → PENDING", deliveryId, previousStatus);

        return RepairActionResult.builder()
                .repairKey(getRepairKey())
                .success(true)
                .dryRun(false)
                .beforeSnapshotJson(beforeJson)
                .afterSnapshotJson(afterJson)
                .details("WebhookDelivery " + deliveryId + " reset from " + previousStatus + " → PENDING for retry")
                .evaluatedAt(LocalDateTime.now())
                .build();
    }

    private Long parseId(String id) {
        try { return Long.parseLong(id); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid delivery id: " + id); }
    }

    private String snapshot(MerchantWebhookDelivery d) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("endpointId", d.getEndpointId());
            m.put("eventType", d.getEventType());
            m.put("status", d.getStatus());
            m.put("attemptCount", d.getAttemptCount());
            m.put("nextAttemptAt", d.getNextAttemptAt());
            m.put("lastError", d.getLastError());
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) { return "{}"; }
    }
}
