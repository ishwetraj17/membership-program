package com.firstclub.billing.dto;

import com.firstclub.membership.entity.Subscription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response returned by {@code POST /api/v2/subscriptions}.
 *
 * <p>When {@code paymentIntentId} is {@code null} the invoice was fully
 * covered by credit notes and the subscription has been immediately activated.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionV2Response {

    private Long subscriptionId;
    private Long invoiceId;

    /** {@code null} when credits covered the full amount. */
    private Long paymentIntentId;

    /** Gateway client-secret for front-end payment confirmation. {@code null} when no payment needed. */
    private String clientSecret;

    private BigDecimal amountDue;
    private String currency;

    private Subscription.SubscriptionStatus status;
}
