package com.firstclub.merchant.dto;

import com.firstclub.merchant.entity.MerchantUserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for a single merchant-user mapping.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantUserResponseDTO {

    private Long id;
    private Long merchantId;
    private Long userId;
    private String userEmail;
    private String userName;
    private MerchantUserRole role;
    private LocalDateTime createdAt;
}
