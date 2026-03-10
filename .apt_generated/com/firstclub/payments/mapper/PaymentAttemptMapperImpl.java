package com.firstclub.payments.mapper;

import com.firstclub.payments.dto.PaymentAttemptResponseDTO;
import com.firstclub.payments.entity.PaymentAttempt;
import com.firstclub.payments.entity.PaymentIntentV2;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-10T18:17:15+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PaymentAttemptMapperImpl implements PaymentAttemptMapper {

    @Override
    public PaymentAttemptResponseDTO toResponseDTO(PaymentAttempt entity) {
        if ( entity == null ) {
            return null;
        }

        PaymentAttemptResponseDTO.PaymentAttemptResponseDTOBuilder paymentAttemptResponseDTO = PaymentAttemptResponseDTO.builder();

        paymentAttemptResponseDTO.paymentIntentId( entityPaymentIntentId( entity ) );
        paymentAttemptResponseDTO.attemptNumber( entity.getAttemptNumber() );
        paymentAttemptResponseDTO.completedAt( entity.getCompletedAt() );
        paymentAttemptResponseDTO.createdAt( entity.getCreatedAt() );
        paymentAttemptResponseDTO.failureCategory( entity.getFailureCategory() );
        paymentAttemptResponseDTO.gatewayIdempotencyKey( entity.getGatewayIdempotencyKey() );
        paymentAttemptResponseDTO.gatewayName( entity.getGatewayName() );
        paymentAttemptResponseDTO.gatewayReference( entity.getGatewayReference() );
        paymentAttemptResponseDTO.gatewayTransactionId( entity.getGatewayTransactionId() );
        paymentAttemptResponseDTO.id( entity.getId() );
        paymentAttemptResponseDTO.latencyMs( entity.getLatencyMs() );
        paymentAttemptResponseDTO.processorNodeId( entity.getProcessorNodeId() );
        paymentAttemptResponseDTO.requestPayloadHash( entity.getRequestPayloadHash() );
        paymentAttemptResponseDTO.responseCode( entity.getResponseCode() );
        paymentAttemptResponseDTO.responseMessage( entity.getResponseMessage() );
        paymentAttemptResponseDTO.retriable( entity.isRetriable() );
        paymentAttemptResponseDTO.startedAt( entity.getStartedAt() );
        paymentAttemptResponseDTO.status( entity.getStatus() );

        return paymentAttemptResponseDTO.build();
    }

    private Long entityPaymentIntentId(PaymentAttempt paymentAttempt) {
        if ( paymentAttempt == null ) {
            return null;
        }
        PaymentIntentV2 paymentIntent = paymentAttempt.getPaymentIntent();
        if ( paymentIntent == null ) {
            return null;
        }
        Long id = paymentIntent.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
