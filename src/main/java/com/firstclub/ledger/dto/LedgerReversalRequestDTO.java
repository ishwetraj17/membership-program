package com.firstclub.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 10 — Request body for {@code POST /ledger/entries/{id}/reverse}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LedgerReversalRequestDTO {

    /**
     * Mandatory explanation for why the original entry is being reversed.
     * Stored permanently on the reversal entry and never modifiable.
     */
    @NotBlank(message = "Reversal reason is required")
    private String reason;

    /**
     * Optional id of the admin user who is initiating the reversal.
     * Used for audit trail only.
     */
    private Long postedByUserId;
}
