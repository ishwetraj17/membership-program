package com.firstclub.reporting.ops.entity;

import lombok.*;
import java.io.Serializable;

/** Composite PK for {@link PaymentSummaryProjection}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSummaryProjectionId implements Serializable {
    private Long merchantId;
    private Long paymentIntentId;
}
