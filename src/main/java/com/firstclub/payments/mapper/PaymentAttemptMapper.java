package com.firstclub.payments.mapper;

import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.entity.PaymentAttempt;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PaymentAttemptMapper {

    @Mapping(target = "paymentIntentId", source = "paymentIntent.id")
    PaymentAttemptResponseDTO toResponseDTO(PaymentAttempt entity);
}
