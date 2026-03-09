package com.firstclub.payments.mapper;

import com.firstclub.payments.dto.PaymentMethodMandateCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateResponseDTO;
import com.firstclub.payments.entity.PaymentMethodMandate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link PaymentMethodMandate} ↔ DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PaymentMethodMandateMapper {

    @Mapping(target = "paymentMethodId", source = "paymentMethod.id")
    PaymentMethodMandateResponseDTO toResponseDTO(PaymentMethodMandate entity);

    @Mapping(target = "id",              ignore = true)
    @Mapping(target = "paymentMethod",   ignore = true)
    @Mapping(target = "status",          ignore = true)
    @Mapping(target = "approvedAt",      ignore = true)
    @Mapping(target = "revokedAt",       ignore = true)
    @Mapping(target = "createdAt",       ignore = true)
    PaymentMethodMandate toEntity(PaymentMethodMandateCreateRequestDTO dto);
}
