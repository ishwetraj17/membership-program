package com.firstclub.recon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconMismatchResolveRequestDTO {
    private String resolutionNote;
    private Long   ownerUserId;
}
