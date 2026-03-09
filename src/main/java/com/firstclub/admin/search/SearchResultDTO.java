package com.firstclub.admin.search;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * Slim, cross-entity view of a search hit returned by the unified admin search.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Never includes {@code clientSecret}, encrypted PII fields (phone,
 *       billingAddress, shippingAddress), or raw event payload.</li>
 *   <li>The {@code merchantId} field lets callers confirm the result belongs
 *       to the correct merchant (tenant isolation check).</li>
 * </ul>
 */
@Value
@Builder
public class SearchResultDTO {

    /** Which entity was matched. */
    SearchResultType resultType;

    /** Primary key of the matched entity. */
    Long primaryId;

    /** Owning merchant — always present; used to confirm tenant isolation. */
    Long merchantId;

    /** Human-readable label shown in the support UI (e.g. invoice number, email). */
    String displayLabel;

    /** Current lifecycle status string of the entity (e.g. "PAID", "ACTIVE"). */
    String status;

    /** Name of the field that matched the query (e.g. "invoiceNumber", "gatewayTxnId"). */
    String matchedField;

    /** Actual value of that field (echoed back for confirmation in the UI). */
    String matchedValue;

    /**
     * Relative API path to retrieve the full entity, e.g.
     * {@code /api/v2/admin/timeline/invoice/42}.
     */
    String apiPath;

    LocalDateTime createdAt;
}
