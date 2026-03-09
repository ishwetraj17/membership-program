package com.firstclub.subscription.mapper;

import com.firstclub.subscription.dto.SubscriptionCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionResponseDTO;
import com.firstclub.subscription.entity.SubscriptionV2;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link SubscriptionV2} ↔ DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface SubscriptionV2Mapper {

    @Mapping(target = "merchantId",    source = "merchant.id")
    @Mapping(target = "customerId",    source = "customer.id")
    @Mapping(target = "productId",     source = "product.id")
    @Mapping(target = "priceId",       source = "price.id")
    @Mapping(target = "priceVersionId", source = "priceVersion.id")
    SubscriptionResponseDTO toResponseDTO(SubscriptionV2 entity);

    @Mapping(target = "id",             ignore = true)
    @Mapping(target = "merchant",       ignore = true)
    @Mapping(target = "customer",       ignore = true)
    @Mapping(target = "product",        ignore = true)
    @Mapping(target = "price",          ignore = true)
    @Mapping(target = "priceVersion",   ignore = true)
    @Mapping(target = "status",         ignore = true)
    @Mapping(target = "billingAnchorAt", ignore = true)
    @Mapping(target = "currentPeriodStart", ignore = true)
    @Mapping(target = "currentPeriodEnd",   ignore = true)
    @Mapping(target = "nextBillingAt",  ignore = true)
    @Mapping(target = "cancelAtPeriodEnd", ignore = true)
    @Mapping(target = "cancelledAt",    ignore = true)
    @Mapping(target = "pauseStartsAt",  ignore = true)
    @Mapping(target = "pauseEndsAt",    ignore = true)
    @Mapping(target = "trialEndsAt",    ignore = true)
    @Mapping(target = "version",        ignore = true)
    @Mapping(target = "createdAt",      ignore = true)
    @Mapping(target = "updatedAt",      ignore = true)
    @Mapping(target = "schedules",      ignore = true)
    SubscriptionV2 toEntity(SubscriptionCreateRequestDTO dto);
}
