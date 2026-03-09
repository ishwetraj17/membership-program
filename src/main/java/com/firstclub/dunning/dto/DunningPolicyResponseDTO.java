package com.firstclub.dunning.dto;

import com.firstclub.dunning.entity.DunningTerminalStatus;
import lombok.*;

import java.time.LocalDateTime;

/** Response DTO for a dunning policy. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DunningPolicyResponseDTO {

    private Long id;
    private Long merchantId;
    private String policyCode;
    private String retryOffsetsJson;
    private int maxAttempts;
    private int graceDays;
    private boolean fallbackToBackupPaymentMethod;
    private DunningTerminalStatus statusAfterExhaustion;
    private LocalDateTime createdAt;
}
