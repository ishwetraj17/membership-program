package com.firstclub.payments.mapper;

import com.firstclub.customer.entity.Customer;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.payments.dto.PaymentMethodCreateRequestDTO;
import com.firstclub.payments.dto.PaymentMethodResponseDTO;
import com.firstclub.payments.entity.PaymentMethod;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-11T21:58:15+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PaymentMethodMapperImpl implements PaymentMethodMapper {

    @Override
    public PaymentMethodResponseDTO toResponseDTO(PaymentMethod entity) {
        if ( entity == null ) {
            return null;
        }

        PaymentMethodResponseDTO.PaymentMethodResponseDTOBuilder paymentMethodResponseDTO = PaymentMethodResponseDTO.builder();

        paymentMethodResponseDTO.merchantId( entityMerchantId( entity ) );
        paymentMethodResponseDTO.customerId( entityCustomerId( entity ) );
        paymentMethodResponseDTO.brand( entity.getBrand() );
        paymentMethodResponseDTO.createdAt( entity.getCreatedAt() );
        paymentMethodResponseDTO.fingerprint( entity.getFingerprint() );
        paymentMethodResponseDTO.id( entity.getId() );
        paymentMethodResponseDTO.last4( entity.getLast4() );
        paymentMethodResponseDTO.methodType( entity.getMethodType() );
        paymentMethodResponseDTO.provider( entity.getProvider() );
        paymentMethodResponseDTO.providerToken( entity.getProviderToken() );
        paymentMethodResponseDTO.status( entity.getStatus() );
        paymentMethodResponseDTO.updatedAt( entity.getUpdatedAt() );

        return paymentMethodResponseDTO.build();
    }

    @Override
    public PaymentMethod toEntity(PaymentMethodCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        PaymentMethod.PaymentMethodBuilder paymentMethod = PaymentMethod.builder();

        paymentMethod.brand( dto.getBrand() );
        paymentMethod.fingerprint( dto.getFingerprint() );
        paymentMethod.last4( dto.getLast4() );
        paymentMethod.methodType( dto.getMethodType() );
        paymentMethod.provider( dto.getProvider() );
        paymentMethod.providerToken( dto.getProviderToken() );

        return paymentMethod.build();
    }

    private Long entityMerchantId(PaymentMethod paymentMethod) {
        if ( paymentMethod == null ) {
            return null;
        }
        MerchantAccount merchant = paymentMethod.getMerchant();
        if ( merchant == null ) {
            return null;
        }
        Long id = merchant.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long entityCustomerId(PaymentMethod paymentMethod) {
        if ( paymentMethod == null ) {
            return null;
        }
        Customer customer = paymentMethod.getCustomer();
        if ( customer == null ) {
            return null;
        }
        Long id = customer.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
