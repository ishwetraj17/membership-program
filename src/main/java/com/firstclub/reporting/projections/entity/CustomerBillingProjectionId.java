package com.firstclub.reporting.projections.entity;

import lombok.*;

import java.io.Serializable;

/** Composite primary-key class for {@link CustomerBillingSummaryProjection}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerBillingProjectionId implements Serializable {
    private Long merchantId;
    private Long customerId;
}
