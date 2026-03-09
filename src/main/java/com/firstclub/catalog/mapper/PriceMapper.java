package com.firstclub.catalog.mapper;

import com.firstclub.catalog.dto.PriceCreateRequestDTO;
import com.firstclub.catalog.dto.PriceResponseDTO;
import com.firstclub.catalog.dto.PriceUpdateRequestDTO;
import com.firstclub.catalog.entity.Price;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link Price} ↔ DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PriceMapper {

    @Mapping(target = "merchantId", source = "merchant.id")
    @Mapping(target = "productId",  source = "product.id")
    PriceResponseDTO toResponseDTO(Price price);

    @Mapping(target = "id",                   ignore = true)
    @Mapping(target = "merchant",             ignore = true)
    @Mapping(target = "product",              ignore = true)
    @Mapping(target = "active",               ignore = true)
    @Mapping(target = "versions",             ignore = true)
    @Mapping(target = "createdAt",            ignore = true)
    @Mapping(target = "updatedAt",            ignore = true)
    Price toEntity(PriceCreateRequestDTO dto);

    @Mapping(target = "id",                   ignore = true)
    @Mapping(target = "merchant",             ignore = true)
    @Mapping(target = "product",              ignore = true)
    @Mapping(target = "priceCode",            ignore = true)
    @Mapping(target = "billingType",          ignore = true)
    @Mapping(target = "currency",             ignore = true)
    @Mapping(target = "amount",               ignore = true)
    @Mapping(target = "billingIntervalUnit",  ignore = true)
    @Mapping(target = "billingIntervalCount", ignore = true)
    @Mapping(target = "active",               ignore = true)
    @Mapping(target = "versions",             ignore = true)
    @Mapping(target = "createdAt",            ignore = true)
    @Mapping(target = "updatedAt",            ignore = true)
    void updateEntityFromDTO(PriceUpdateRequestDTO dto, @MappingTarget Price entity);
}
