package com.firstclub.dunning.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request payload for setting (or replacing) the payment method preferences
 * for a subscription.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPaymentPreferenceRequestDTO {

    @NotNull
    private Long primaryPaymentMethodId;

    /** Optional. Must differ from {@code primaryPaymentMethodId} when provided. */
    private Long backupPaymentMethodId;

    /** Optional JSON array encoding an explicit retry order. Reserved for future use. */
    private String retryOrderJson;
}
