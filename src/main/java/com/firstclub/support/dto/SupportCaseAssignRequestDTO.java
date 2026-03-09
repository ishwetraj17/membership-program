package com.firstclub.support.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for assigning a support case to a platform operator.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportCaseAssignRequestDTO {

    @NotNull(message = "ownerUserId is required")
    private Long ownerUserId;
}
