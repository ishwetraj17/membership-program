package com.firstclub.platform.version.dto;

import com.firstclub.platform.version.MerchantApiVersion;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response for {@code GET /merchants/{merchantId}/api-version} and
 * {@code PUT /merchants/{merchantId}/api-version}.
 */
public record ApiVersionPinResponseDTO(
        Long   id,
        Long   merchantId,
        String pinnedVersion,
        LocalDate effectiveFrom,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ApiVersionPinResponseDTO from(MerchantApiVersion pin) {
        return new ApiVersionPinResponseDTO(
                pin.getId(),
                pin.getMerchantId(),
                pin.getPinnedVersion(),
                pin.getEffectiveFrom(),
                pin.getCreatedAt(),
                pin.getUpdatedAt()
        );
    }
}
