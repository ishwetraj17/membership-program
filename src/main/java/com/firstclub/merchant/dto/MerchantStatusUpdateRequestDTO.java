package com.firstclub.merchant.dto;

import com.firstclub.merchant.entity.MerchantStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code PUT /api/v2/admin/merchants/{id}/status}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantStatusUpdateRequestDTO {

    @NotNull(message = "status is required")
    private MerchantStatus status;

    /** Optional human-readable reason stored in audit log. */
    private String reason;
}
