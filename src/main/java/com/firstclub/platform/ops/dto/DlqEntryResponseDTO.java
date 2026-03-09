package com.firstclub.platform.ops.dto;

import com.firstclub.payments.entity.DeadLetterMessage;

import java.time.LocalDateTime;

/**
 * Read-only view of one DLQ entry.
 *
 * <p>Phase 16 additions:
 * <ul>
 *   <li>{@code outboxEventType} — real event type parsed from the pipe-delimited payload
 *       (was previously buried as the prefix of {@code payload})</li>
 *   <li>{@code failureCategory} — coarse failure class for ops triage</li>
 *   <li>{@code merchantId} — merchant the event belongs to (nullable)</li>
 * </ul>
 */
public record DlqEntryResponseDTO(
        Long          id,
        String        source,
        String        payload,
        String        error,
        LocalDateTime createdAt,
        String        outboxEventType,
        String        failureCategory,
        Long          merchantId
) {
    public static DlqEntryResponseDTO from(DeadLetterMessage m) {
        return new DlqEntryResponseDTO(
                m.getId(),
                m.getSource(),
                m.getPayload(),
                m.getError(),
                m.getCreatedAt(),
                parseEventType(m.getPayload()),
                m.getFailureCategory(),
                m.getMerchantId());
    }

    /**
     * Extracts the real event type from the pipe-delimited OUTBOX payload.
     * For OUTBOX entries the payload format is {@code {eventType}|{json}}.
     * For WEBHOOK entries the payload is raw; returns {@code null}.
     */
    static String parseEventType(String payload) {
        if (payload != null && payload.contains("|")) {
            return payload.split("\\|", 2)[0];
        }
        return null;
    }
}
