package com.firstclub.reporting.ops.entity;

import lombok.*;
import java.io.Serializable;

/** Composite PK for {@link SubscriptionStatusProjection}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionStatusProjectionId implements Serializable {
    private Long merchantId;
    private Long subscriptionId;
}
