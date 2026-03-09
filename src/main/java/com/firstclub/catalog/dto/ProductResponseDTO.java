package com.firstclub.catalog.dto;

import com.firstclub.catalog.entity.ProductStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response payload for a {@link com.firstclub.catalog.entity.Product}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductResponseDTO {

    private Long id;
    private Long merchantId;
    private String productCode;
    private String name;
    private String description;
    private ProductStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
