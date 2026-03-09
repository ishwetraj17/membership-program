package com.firstclub.payments.disputes.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Response DTO for a single piece of dispute evidence. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeEvidenceResponseDTO {

    private Long          id;
    private Long          disputeId;
    private String        evidenceType;
    private String        contentReference;
    private Long          uploadedBy;
    private LocalDateTime createdAt;
}
