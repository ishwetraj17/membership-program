package com.firstclub.payments.mapper;

import com.firstclub.payments.dto.PaymentMethodMandateCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodMandateResponseDTO;
import com.firstclub.payments.entity.PaymentMethod;
import com.firstclub.payments.entity.PaymentMethodMandate;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-09T14:26:50+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PaymentMethodMandateMapperImpl implements PaymentMethodMandateMapper {

    @Override
    public PaymentMethodMandateResponseDTO toResponseDTO(PaymentMethodMandate entity) {
        if ( entity == null ) {
            return null;
        }

        PaymentMethodMandateResponseDTO.PaymentMethodMandateResponseDTOBuilder paymentMethodMandateResponseDTO = PaymentMethodMandateResponseDTO.builder();

        paymentMethodMandateResponseDTO.paymentMethodId( entityPaymentMethodId( entity ) );
        paymentMethodMandateResponseDTO.approvedAt( entity.getApprovedAt() );
        paymentMethodMandateResponseDTO.createdAt( entity.getCreatedAt() );
        paymentMethodMandateResponseDTO.currency( entity.getCurrency() );
        paymentMethodMandateResponseDTO.id( entity.getId() );
        paymentMethodMandateResponseDTO.mandateReference( entity.getMandateReference() );
        paymentMethodMandateResponseDTO.maxAmount( entity.getMaxAmount() );
        paymentMethodMandateResponseDTO.revokedAt( entity.getRevokedAt() );
        paymentMethodMandateResponseDTO.status( entity.getStatus() );

        return paymentMethodMandateResponseDTO.build();
    }

    @Override
    public PaymentMethodMandate toEntity(PaymentMethodMandateCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        PaymentMethodMandate.PaymentMethodMandateBuilder paymentMethodMandate = PaymentMethodMandate.builder();

        paymentMethodMandate.currency( dto.getCurrency() );
        paymentMethodMandate.mandateReference( dto.getMandateReference() );
        paymentMethodMandate.maxAmount( dto.getMaxAmount() );

        return paymentMethodMandate.build();
    }

    private Long entityPaymentMethodId(PaymentMethodMandate paymentMethodMandate) {
        if ( paymentMethodMandate == null ) {
            return null;
        }
        PaymentMethod paymentMethod = paymentMethodMandate.getPaymentMethod();
        if ( paymentMethod == null ) {
            return null;
        }
        Long id = paymentMethod.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
