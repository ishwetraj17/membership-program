package com.firstclub.payments.job;

import com.firstclub.payments.entity.WebhookEvent;
import com.firstclub.payments.repository.WebhookEventRepository;
import com.firstclub.payments.service.WebhookProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Background job that picks up webhook events that failed on first delivery and
 * retries them with exponential back-off.
 *
 * <p>Schedule: every 30 seconds (fixed delay, so runs at most once per cycle
 * regardless of how long processing takes).
 *
 * <p>An event is eligible when:
 * <ul>
 *   <li>{@code processed = false}
 *   <li>{@code signature_valid = true}
 *   <li>{@code next_attempt_at <= now}
 *   <li>{@code attempts < MAX_ATTEMPTS}
 * </ul>
 *
 * <p>Permanently failed events (attempts ≥ {@link WebhookProcessingService#MAX_ATTEMPTS})
 * remain in the table for audit and are also mirrored to {@code dead_letter_messages}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookRetryJob {

    private final WebhookEventRepository webhookEventRepository;
    private final WebhookProcessingService webhookProcessingService;

    @Scheduled(fixedDelay = 30_000)
    public void retryUnprocessed() {
        List<WebhookEvent> eligible = webhookEventRepository.findEligibleForRetry(
                LocalDateTime.now(),
                WebhookProcessingService.MAX_ATTEMPTS);

        if (eligible.isEmpty()) {
            return;
        }

        log.info("WebhookRetryJob: {} event(s) eligible for retry", eligible.size());

        for (WebhookEvent event : eligible) {
            try {
                webhookProcessingService.processStoredEvent(event);
            } catch (Exception ex) {
                // processStoredEvent already updates the event record and writes to dead-letter.
                // Log here so other events in the batch still run.
                log.error("Retry failed for webhook event {}: {}", event.getEventId(), ex.getMessage());
            }
        }
    }
}
