package com.firstclub.payments.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payload serialised by the fake gateway emulator and delivered as the webhook
 * request body.  The body is signed with HMAC-SHA256 and the hex digest is
 * placed in the {@code X-Signature} header.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookPayloadDTO {

    /** Unique event identifier — used for idempotency. */
    private String eventId;

    /** e.g. PAYMENT_INTENT.SUCCEEDED, PAYMENT_INTENT.FAILED */
    private String eventType;

    private Long paymentIntentId;

    private BigDecimal amount;

    private String currency;

    /** Gateway-assigned transaction identifier for SUCCEEDED/FAILED events. */
    private String gatewayTxnId;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;
}
