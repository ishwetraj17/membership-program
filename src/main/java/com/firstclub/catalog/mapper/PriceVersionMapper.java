package com.firstclub.catalog.mapper;

import com.firstclub.catalog.dto.PriceVersionCreateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;
import com.firstclub.catalog.entity.PriceVersion;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link PriceVersion} ↔ DTOs.
 */
@Mapper(componentModel = "spring")
public interface PriceVersionMapper {

    @Mapping(target = "priceId", source = "price.id")
    PriceVersionResponseDTO toResponseDTO(PriceVersion version);

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "price",     ignore = true)
    @Mapping(target = "effectiveTo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    PriceVersion toEntity(PriceVersionCreateRequestDTO dto);
}
