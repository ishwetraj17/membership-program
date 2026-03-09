package com.firstclub.subscription.mapper;

import com.firstclub.subscription.dto.SubscriptionScheduleCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionScheduleResponseDTO;
import com.firstclub.subscription.entity.SubscriptionSchedule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link SubscriptionSchedule} ↔ DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface SubscriptionScheduleMapper {

    @Mapping(target = "subscriptionId", source = "subscription.id")
    SubscriptionScheduleResponseDTO toResponseDTO(SubscriptionSchedule entity);

    @Mapping(target = "id",           ignore = true)
    @Mapping(target = "subscription", ignore = true)
    @Mapping(target = "status",       ignore = true)
    @Mapping(target = "createdAt",    ignore = true)
    @Mapping(target = "updatedAt",    ignore = true)
    SubscriptionSchedule toEntity(SubscriptionScheduleCreateRequestDTO dto);
}
