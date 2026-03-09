package com.firstclub.support.dto;

import com.firstclub.support.entity.SupportNoteVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for adding a note to an existing support case.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportNoteCreateRequestDTO {

    @NotBlank(message = "noteText is required")
    private String noteText;

    @NotNull(message = "authorUserId is required")
    private Long authorUserId;

    /** Defaults to {@link SupportNoteVisibility#INTERNAL_ONLY} when null. */
    private SupportNoteVisibility visibility;
}
