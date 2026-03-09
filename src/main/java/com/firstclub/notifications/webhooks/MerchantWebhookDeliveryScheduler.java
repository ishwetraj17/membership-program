package com.firstclub.notifications.webhooks;

import com.firstclub.notifications.webhooks.service.MerchantWebhookDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for and dispatches due outbound webhook deliveries every 60 seconds.
 *
 * <p>Runs independently of the dunning schedulers (initial delay = 135 s)
 * to avoid thundering-herd effects at startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MerchantWebhookDeliveryScheduler {

    private final MerchantWebhookDeliveryService deliveryService;

    @Scheduled(fixedRate = 60_000, initialDelay = 135_000)
    public void processDeliveries() {
        log.debug("Polling for due merchant webhook deliveries");
        try {
            deliveryService.retryDueDeliveries();
        } catch (Exception e) {
            // Never let the scheduler die
            log.error("Error in MerchantWebhookDeliveryScheduler: {}", e.getMessage(), e);
        }
    }
}
