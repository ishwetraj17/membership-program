package com.firstclub.support.dto;

import com.firstclub.support.entity.SupportCasePriority;
import com.firstclub.support.entity.SupportCaseStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for a support case.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportCaseResponseDTO {

    private Long id;
    private Long merchantId;
    private String linkedEntityType;
    private Long linkedEntityId;
    private String title;
    private SupportCaseStatus status;
    private SupportCasePriority priority;
    private Long ownerUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
