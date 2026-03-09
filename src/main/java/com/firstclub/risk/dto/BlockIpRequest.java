package com.firstclub.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/admin/risk/block/ip.
 */
public record BlockIpRequest(

        @NotBlank(message = "ip is required")
        @Size(max = 64, message = "ip must not exceed 64 characters")
        String ip,

        @NotBlank(message = "reason is required")
        @Size(max = 255, message = "reason must not exceed 255 characters")
        String reason
) {}
