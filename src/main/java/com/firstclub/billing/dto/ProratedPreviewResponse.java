package com.firstclub.billing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for {@code GET /api/v2/subscriptions/{id}/change-preview?newPlanId=...}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProratedPreviewResponse {

    private Long currentPlanId;
    private Long newPlanId;

    /** Itemised proration lines (PRORATION credit + PLAN_CHARGE). */
    private List<InvoiceLineDTO> lines;

    /** Algebraic sum of all line amounts (may be negative if credit > charge). */
    private BigDecimal total;

    /** Amount the customer will actually be charged: {@code max(0, total)}. */
    private BigDecimal amountDue;
}
