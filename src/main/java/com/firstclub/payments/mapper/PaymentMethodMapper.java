package com.firstclub.payments.mapper;

import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.PaymentMethod;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link PaymentMethod} ↔ DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PaymentMethodMapper {

    @Mapping(target = "merchantId", source = "merchant.id")
    @Mapping(target = "customerId",  source = "customer.id")
    PaymentMethodResponseDTO toResponseDTO(PaymentMethod entity);

    @Mapping(target = "id",        ignore = true)
    @Mapping(target = "merchant",  ignore = true)
    @Mapping(target = "customer",  ignore = true)
    @Mapping(target = "status",    ignore = true)
    @Mapping(target = "isDefault", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "mandates",  ignore = true)
    PaymentMethod toEntity(PaymentMethodCreateRequestDTO dto);
}
