package com.firstclub.reporting.projections.entity;

import lombok.*;

import java.io.Serializable;

/**
 * Composite primary key for {@link LedgerBalanceProjection}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class LedgerBalanceProjectionId implements Serializable {
    private Long merchantId;
    private Long userId;
}
