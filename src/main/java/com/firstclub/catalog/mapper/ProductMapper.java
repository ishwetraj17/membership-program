package com.firstclub.catalog.mapper;

import com.firstclub.catalog.dto.ProductCreateRequestDTO;
import com.firstclub.catalog.dto.ProductResponseDTO;
import com.firstclub.catalog.dto.ProductUpdateRequestDTO;
import com.firstclub.catalog.entity.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link Product} ↔ DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ProductMapper {

    @Mapping(target = "merchantId", source = "merchant.id")
    ProductResponseDTO toResponseDTO(Product product);

    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "merchant",    ignore = true)
    @Mapping(target = "status",      ignore = true)
    @Mapping(target = "prices",      ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    Product toEntity(ProductCreateRequestDTO dto);

    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "merchant",    ignore = true)
    @Mapping(target = "productCode", ignore = true)
    @Mapping(target = "status",      ignore = true)
    @Mapping(target = "prices",      ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    void updateEntityFromDTO(ProductUpdateRequestDTO dto, @MappingTarget Product entity);
}
