package com.firstclub.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a new {@link com.firstclub.catalog.entity.Product}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCreateRequestDTO {

    @NotBlank(message = "productCode is required")
    @Size(max = 64, message = "productCode must not exceed 64 characters")
    private String productCode;

    @NotBlank(message = "name is required")
    @Size(max = 255, message = "name must not exceed 255 characters")
    private String name;

    private String description;
}
