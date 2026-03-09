package com.firstclub.support.dto;

import com.firstclub.support.entity.SupportCasePriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for opening a new support case.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportCaseCreateRequestDTO {

    @NotNull(message = "merchantId is required")
    private Long merchantId;

    /** Entity type constant — e.g. CUSTOMER, INVOICE, DISPUTE. */
    @NotBlank(message = "linkedEntityType is required")
    private String linkedEntityType;

    @NotNull(message = "linkedEntityId is required")
    private Long linkedEntityId;

    @NotBlank(message = "title is required")
    @Size(max = 255, message = "title must be 255 characters or fewer")
    private String title;

    /** Defaults to {@link SupportCasePriority#MEDIUM} when null. */
    private SupportCasePriority priority;
}
