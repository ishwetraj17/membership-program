package com.firstclub.merchant.mapper;

import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantSettingsDTO;
import com.firstclub.merchant.dto.MerchantUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantSettings;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link MerchantAccount} ↔ response DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface MerchantMapper {

    /**
     * Maps a MerchantAccount entity to its response DTO.
     * The nested settings are mapped via {@link #settingsToDTO}.
     */
    @Mapping(target = "settings", source = "settings")
    MerchantResponseDTO toResponseDTO(MerchantAccount merchant);

    /**
     * Maps MerchantSettings entity to its embedded DTO.
     */
    MerchantSettingsDTO settingsToDTO(MerchantSettings settings);

    /**
     * Applies non-null fields from an update request onto an existing entity.
     * Immutable fields (id, merchantCode, timestamps, associations) are ignored.
     */
    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "merchantCode", ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    @Mapping(target = "merchantUsers",ignore = true)
    @Mapping(target = "settings",     ignore = true)
    @Mapping(target = "status",       ignore = true)
    void updateEntityFromDTO(MerchantUpdateRequestDTO dto, @MappingTarget MerchantAccount entity);
}
