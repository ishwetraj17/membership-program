package com.firstclub.merchant.dto;

import com.firstclub.merchant.entity.MerchantUserRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/v2/admin/merchants/{merchantId}/users}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantUserCreateRequestDTO {

    @NotNull(message = "userId is required")
    private Long userId;

    @NotNull(message = "role is required")
    private MerchantUserRole role;
}
