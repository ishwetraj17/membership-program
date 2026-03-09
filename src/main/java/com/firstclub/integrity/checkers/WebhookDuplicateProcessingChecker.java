package com.firstclub.integrity.checkers;

import com.firstclub.integrity.InvariantChecker;
import com.firstclub.integrity.InvariantResult;
import com.firstclub.integrity.InvariantSeverity;
import com.firstclub.integrity.InvariantViolation;
import com.firstclub.payments.entity.WebhookEvent;
import com.firstclub.payments.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects webhook events that are permanently stuck — they have a valid signature,
 * have not been processed, and have exhausted the maximum retry budget.
 *
 * <p>A stuck valid webhook means a business event (e.g. payment.succeeded) was
 * delivered by the gateway but never acted upon, which may leave the platform in
 * an inconsistent state (captured payment not reflected in the ledger, invoice left
 * OPEN, etc.).
 */
@Component
@RequiredArgsConstructor
public class WebhookDuplicateProcessingChecker implements InvariantChecker {

    public static final String NAME = "WEBHOOK_DUPLICATE_PROCESSING";
    /** Events are considered permanently stuck once they exceed this attempt limit. */
    static final int MAX_ATTEMPTS = 10;
    private static final String REPAIR =
            "For each stuck webhook: manually inspect the payload and replay it via "
            + "WebhookProcessingService.process(webhookId). Verify the downstream side-effects "
            + "(ledger entry, invoice status update) were applied. After successful replay, "
            + "mark the event as processed=true and reset attempts.";

    private final WebhookEventRepository webhookEventRepository;

    @Override public String getName()               { return NAME; }
    @Override public InvariantSeverity getSeverity() { return InvariantSeverity.MEDIUM; }

    @Override
    public InvariantResult check() {
        List<WebhookEvent> stuck = webhookEventRepository.findStuckWebhooks(MAX_ATTEMPTS);

        if (stuck.isEmpty()) {
            return InvariantResult.pass(NAME, getSeverity());
        }

        List<InvariantViolation> violations = new ArrayList<>();
        for (WebhookEvent event : stuck) {
            violations.add(InvariantViolation.builder()
                    .entityType("WebhookEvent")
                    .entityId(String.valueOf(event.getId()))
                    .description(String.format(
                            "WebhookEvent %d (provider=%s, eventId=%s, type=%s) is unprocessed "
                            + "with %d attempts — permanently stuck, downstream processing may be missing",
                            event.getId(), event.getProvider(), event.getEventId(),
                            event.getEventType(), event.getAttempts()))
                    .suggestedRepairAction(REPAIR)
                    .build());
        }

        return InvariantResult.fail(NAME, getSeverity(), violations);
    }
}
