package com.firstclub.payments.disputes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for submitting a piece of evidence against a dispute. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeEvidenceCreateRequestDTO {

    /** Structured type, e.g. INVOICE, DELIVERY_PROOF, CORRESPONDENCE, SCREENSHOT. */
    @NotBlank
    @Size(max = 64)
    private String evidenceType;

    /**
     * URI, S3 key, document ID, or inline text excerpt identifying the evidence.
     * Callers are responsible for providing a stable, retrievable reference.
     */
    @NotBlank
    private String contentReference;

    /** Platform user (operator/admin) uploading the evidence. */
    @NotNull
    private Long uploadedBy;
}
