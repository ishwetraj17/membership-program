package com.firstclub.customer.dto;

import com.firstclub.customer.entity.CustomerNoteVisibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for a customer note.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerNoteResponseDTO {

    private Long id;
    private Long customerId;
    private Long authorUserId;
    private String authorName;
    private String noteText;
    private CustomerNoteVisibility visibility;
    private LocalDateTime createdAt;
}
