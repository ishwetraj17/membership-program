package com.firstclub.subscription.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a new subscription contract (v2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionCreateRequestDTO {

    /** Customer to subscribe. Must belong to the same merchant. */
    @NotNull
    private Long customerId;

    /** Product being subscribed to. Must belong to the same merchant. */
    @NotNull
    private Long productId;

    /** The price variant to use. Must belong to the same merchant and product. */
    @NotNull
    private Long priceId;

    /**
     * Optional: pin to a specific price version.
     * If omitted, the service will resolve the currently-effective version.
     */
    private Long priceVersionId;

    /**
     * Optional free-form JSON metadata supplied by the merchant.
     * Stored as-is on the subscription; not validated server-side.
     */
    private String metadataJson;
}
