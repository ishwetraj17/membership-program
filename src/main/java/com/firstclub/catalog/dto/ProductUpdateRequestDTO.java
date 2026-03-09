package com.firstclub.catalog.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for updating a {@link com.firstclub.catalog.entity.Product}.
 *
 * <p>Patch semantics — {@code null} fields leave the existing value unchanged
 * (enforced by {@code NullValuePropertyMappingStrategy.IGNORE} in the mapper).
 * {@code productCode} is immutable after creation and is therefore absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductUpdateRequestDTO {

    @Size(max = 255, message = "name must not exceed 255 characters")
    private String name;

    private String description;
}
