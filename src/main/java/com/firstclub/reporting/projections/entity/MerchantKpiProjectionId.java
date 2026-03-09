package com.firstclub.reporting.projections.entity;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDate;

/** Composite primary-key class for {@link MerchantDailyKpiProjection}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantKpiProjectionId implements Serializable {
    private Long merchantId;
    private LocalDate businessDate;
}
