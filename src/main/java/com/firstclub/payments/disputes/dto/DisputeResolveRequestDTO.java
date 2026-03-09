package com.firstclub.payments.disputes.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for resolving a dispute.
 * {@code outcome} must be {@code "WON"} or {@code "LOST"} (case-insensitive).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeResolveRequestDTO {

    /**
     * Resolution outcome.  Accepted values: {@code WON}, {@code LOST} (case-insensitive).
     * <ul>
     *   <li>WON  — reserve released, payment status restored.</li>
     *   <li>LOST — chargeback expense posted, captured amount permanently reduced.</li>
     * </ul>
     */
    @NotBlank
    private String outcome;

    /** Optional notes captured for audit trail. Not persisted to the dispute record. */
    private String resolutionNotes;
}
