package com.firstclub.reporting.projections.entity;

import lombok.*;

import java.io.Serializable;

/**
 * Composite primary key for {@link CustomerPaymentSummaryProjection}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class CustomerPaymentSummaryProjectionId implements Serializable {
    private Long merchantId;
    private Long customerId;
}
