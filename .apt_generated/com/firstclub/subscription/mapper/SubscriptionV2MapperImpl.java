package com.firstclub.subscription.mapper;

import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.PriceVersion;
import com.firstclub.catalog.entity.Product;
import com.firstclub.customer.entity.Customer;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.subscription.dto.SubscriptionCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionResponseDTO;
import com.firstclub.subscription.entity.SubscriptionV2;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-12T15:43:32+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class SubscriptionV2MapperImpl implements SubscriptionV2Mapper {

    @Override
    public SubscriptionResponseDTO toResponseDTO(SubscriptionV2 entity) {
        if ( entity == null ) {
            return null;
        }

        SubscriptionResponseDTO.SubscriptionResponseDTOBuilder subscriptionResponseDTO = SubscriptionResponseDTO.builder();

        subscriptionResponseDTO.merchantId( entityMerchantId( entity ) );
        subscriptionResponseDTO.customerId( entityCustomerId( entity ) );
        subscriptionResponseDTO.productId( entityProductId( entity ) );
        subscriptionResponseDTO.priceId( entityPriceId( entity ) );
        subscriptionResponseDTO.priceVersionId( entityPriceVersionId( entity ) );
        subscriptionResponseDTO.billingAnchorAt( entity.getBillingAnchorAt() );
        subscriptionResponseDTO.cancelAtPeriodEnd( entity.isCancelAtPeriodEnd() );
        subscriptionResponseDTO.cancelledAt( entity.getCancelledAt() );
        subscriptionResponseDTO.createdAt( entity.getCreatedAt() );
        subscriptionResponseDTO.currentPeriodEnd( entity.getCurrentPeriodEnd() );
        subscriptionResponseDTO.currentPeriodStart( entity.getCurrentPeriodStart() );
        subscriptionResponseDTO.id( entity.getId() );
        subscriptionResponseDTO.metadataJson( entity.getMetadataJson() );
        subscriptionResponseDTO.nextBillingAt( entity.getNextBillingAt() );
        subscriptionResponseDTO.pauseEndsAt( entity.getPauseEndsAt() );
        subscriptionResponseDTO.pauseStartsAt( entity.getPauseStartsAt() );
        subscriptionResponseDTO.status( entity.getStatus() );
        subscriptionResponseDTO.trialEndsAt( entity.getTrialEndsAt() );
        subscriptionResponseDTO.updatedAt( entity.getUpdatedAt() );
        subscriptionResponseDTO.version( entity.getVersion() );

        return subscriptionResponseDTO.build();
    }

    @Override
    public SubscriptionV2 toEntity(SubscriptionCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        SubscriptionV2.SubscriptionV2Builder subscriptionV2 = SubscriptionV2.builder();

        subscriptionV2.metadataJson( dto.getMetadataJson() );

        return subscriptionV2.build();
    }

    private Long entityMerchantId(SubscriptionV2 subscriptionV2) {
        if ( subscriptionV2 == null ) {
            return null;
        }
        MerchantAccount merchant = subscriptionV2.getMerchant();
        if ( merchant == null ) {
            return null;
        }
        Long id = merchant.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long entityCustomerId(SubscriptionV2 subscriptionV2) {
        if ( subscriptionV2 == null ) {
            return null;
        }
        Customer customer = subscriptionV2.getCustomer();
        if ( customer == null ) {
            return null;
        }
        Long id = customer.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long entityProductId(SubscriptionV2 subscriptionV2) {
        if ( subscriptionV2 == null ) {
            return null;
        }
        Product product = subscriptionV2.getProduct();
        if ( product == null ) {
            return null;
        }
        Long id = product.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long entityPriceId(SubscriptionV2 subscriptionV2) {
        if ( subscriptionV2 == null ) {
            return null;
        }
        Price price = subscriptionV2.getPrice();
        if ( price == null ) {
            return null;
        }
        Long id = price.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long entityPriceVersionId(SubscriptionV2 subscriptionV2) {
        if ( subscriptionV2 == null ) {
            return null;
        }
        PriceVersion priceVersion = subscriptionV2.getPriceVersion();
        if ( priceVersion == null ) {
            return null;
        }
        Long id = priceVersion.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
