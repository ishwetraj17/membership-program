package com.firstclub.payments.mapper;

import com.firstclub.customer.entity.Customer;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.payments.dto.PaymentIntentCreateRequestDTO;
import com.firstclub.payments.dto.PaymentIntentV2ResponseDTO;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentMethod;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-10T12:48:21+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PaymentIntentV2MapperImpl implements PaymentIntentV2Mapper {

    @Override
    public PaymentIntentV2ResponseDTO toResponseDTO(PaymentIntentV2 entity) {
        if ( entity == null ) {
            return null;
        }

        PaymentIntentV2ResponseDTO.PaymentIntentV2ResponseDTOBuilder paymentIntentV2ResponseDTO = PaymentIntentV2ResponseDTO.builder();

        paymentIntentV2ResponseDTO.merchantId( entityMerchantId( entity ) );
        paymentIntentV2ResponseDTO.customerId( entityCustomerId( entity ) );
        paymentIntentV2ResponseDTO.paymentMethodId( entityPaymentMethodId( entity ) );
        paymentIntentV2ResponseDTO.amount( entity.getAmount() );
        paymentIntentV2ResponseDTO.captureMode( entity.getCaptureMode() );
        paymentIntentV2ResponseDTO.clientSecret( entity.getClientSecret() );
        paymentIntentV2ResponseDTO.createdAt( entity.getCreatedAt() );
        paymentIntentV2ResponseDTO.currency( entity.getCurrency() );
        paymentIntentV2ResponseDTO.id( entity.getId() );
        paymentIntentV2ResponseDTO.idempotencyKey( entity.getIdempotencyKey() );
        paymentIntentV2ResponseDTO.invoiceId( entity.getInvoiceId() );
        paymentIntentV2ResponseDTO.metadataJson( entity.getMetadataJson() );
        paymentIntentV2ResponseDTO.status( entity.getStatus() );
        paymentIntentV2ResponseDTO.subscriptionId( entity.getSubscriptionId() );
        paymentIntentV2ResponseDTO.updatedAt( entity.getUpdatedAt() );
        paymentIntentV2ResponseDTO.version( entity.getVersion() );

        return paymentIntentV2ResponseDTO.build();
    }

    @Override
    public PaymentIntentV2 toEntity(PaymentIntentCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        PaymentIntentV2.PaymentIntentV2Builder paymentIntentV2 = PaymentIntentV2.builder();

        paymentIntentV2.amount( dto.getAmount() );
        paymentIntentV2.captureMode( dto.getCaptureMode() );
        paymentIntentV2.currency( dto.getCurrency() );
        paymentIntentV2.invoiceId( dto.getInvoiceId() );
        paymentIntentV2.metadataJson( dto.getMetadataJson() );
        paymentIntentV2.subscriptionId( dto.getSubscriptionId() );

        return paymentIntentV2.build();
    }

    private Long entityMerchantId(PaymentIntentV2 paymentIntentV2) {
        if ( paymentIntentV2 == null ) {
            return null;
        }
        MerchantAccount merchant = paymentIntentV2.getMerchant();
        if ( merchant == null ) {
            return null;
        }
        Long id = merchant.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long entityCustomerId(PaymentIntentV2 paymentIntentV2) {
        if ( paymentIntentV2 == null ) {
            return null;
        }
        Customer customer = paymentIntentV2.getCustomer();
        if ( customer == null ) {
            return null;
        }
        Long id = customer.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long entityPaymentMethodId(PaymentIntentV2 paymentIntentV2) {
        if ( paymentIntentV2 == null ) {
            return null;
        }
        PaymentMethod paymentMethod = paymentIntentV2.getPaymentMethod();
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
