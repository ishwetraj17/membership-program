package com.firstclub.reporting.ops.entity;

import lombok.*;
import java.io.Serializable;

/** Composite PK for {@link InvoiceSummaryProjection}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSummaryProjectionId implements Serializable {
    private Long merchantId;
    private Long invoiceId;
}
