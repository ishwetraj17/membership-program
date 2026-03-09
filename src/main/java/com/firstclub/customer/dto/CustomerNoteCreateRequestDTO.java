package com.firstclub.customer.dto;

import com.firstclub.customer.entity.CustomerNoteVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for adding a new note to a customer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerNoteCreateRequestDTO {

    @NotBlank(message = "Note text is required")
    private String noteText;

    @NotNull(message = "Visibility is required")
    private CustomerNoteVisibility visibility;
}
