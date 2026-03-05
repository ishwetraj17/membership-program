package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only preview of what an upgrade will cost before it is committed.
 * Returned by GET /api/v1/subscriptions/{id}/upgrade-preview?newPlanId={x}.
 * No state is changed when this endpoint is called.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpgradePreviewDTO {

    private Long subscriptionId;

    // Current plan snapshot
    private String currentPlanName;
    private String currentTier;
    private BigDecimal currentPlanPrice;

    // Proposed plan snapshot
    private String newPlanName;
    private String newTier;
    private BigDecimal newPlanPrice;

    // Financial impact
    private BigDecimal proRatedDifference;
    private String currency;

    // When the upgrade would take effect (immediately = now)
    private LocalDateTime effectiveDate;
}
