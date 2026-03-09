package com.firstclub.support.dto;

import com.firstclub.support.entity.SupportNoteVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for a support note.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportNoteResponseDTO {

    private Long id;
    private Long caseId;
    private String noteText;
    private Long authorUserId;
    private SupportNoteVisibility visibility;
    private LocalDateTime createdAt;
}
