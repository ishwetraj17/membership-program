package com.firstclub.dunning.dto;

import jakarta.validation.constraints.*;
import lombok.*;

/**
 * Request payload for creating a new dunning policy.
 *
 * <p>{@code retryOffsetsJson} must be a JSON array of positive integers
 * representing the delay in <em>minutes</em> from the failed charge.
 * Example: {@code "[60, 360, 1440, 4320]"} → 1 h / 6 h / 24 h / 3 d.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DunningPolicyCreateRequestDTO {

    @NotBlank
    @Size(max = 64)
    private String policyCode;

    /** JSON array of positive integer offsets in minutes. */
    @NotBlank
    private String retryOffsetsJson;

    @Min(1)
    private int maxAttempts;

    @Min(0)
    private int graceDays;

    private boolean fallbackToBackupPaymentMethod;

    /** Must be {@code SUSPENDED} or {@code CANCELLED}. */
    @NotBlank
    private String statusAfterExhaustion;
}
