package com.firstclub.dunning.dto;

import lombok.*;

import java.time.LocalDateTime;

/** Response DTO for subscription payment preferences. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionPaymentPreferenceResponseDTO {

    private Long id;
    private Long subscriptionId;
    private Long primaryPaymentMethodId;
    private Long backupPaymentMethodId;
    private String retryOrderJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
