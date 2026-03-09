package com.firstclub.payments.mapper;

import com.firstclub.payments.dto.PaymentIntentCreateRequestDTO;
import com.firstclub.payments.dto.PaymentIntentV2ResponseDTO;
import com.firstclub.payments.entity.PaymentIntentV2;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PaymentIntentV2Mapper {

    @Mapping(target = "merchantId",       source = "merchant.id")
    @Mapping(target = "customerId",       source = "customer.id")
    @Mapping(target = "paymentMethodId",  source = "paymentMethod.id")
    PaymentIntentV2ResponseDTO toResponseDTO(PaymentIntentV2 entity);

    @Mapping(target = "id",             ignore = true)
    @Mapping(target = "merchant",       ignore = true)
    @Mapping(target = "customer",       ignore = true)
    @Mapping(target = "paymentMethod",  ignore = true)
    @Mapping(target = "status",         ignore = true)
    @Mapping(target = "clientSecret",   ignore = true)
    @Mapping(target = "idempotencyKey", ignore = true)
    @Mapping(target = "version",        ignore = true)
    @Mapping(target = "createdAt",      ignore = true)
    @Mapping(target = "updatedAt",      ignore = true)
    @Mapping(target = "attempts",       ignore = true)
    PaymentIntentV2 toEntity(PaymentIntentCreateRequestDTO dto);
}
