package com.firstclub.catalog.mapper;

import com.firstclub.catalog.dto.PriceCreateRequestDTO;
import com.firstclub.catalog.dto.PriceResponseDTO;
import com.firstclub.catalog.dto.PriceUpdateRequestDTO;
import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.Product;
import com.firstclub.merchant.entity.MerchantAccount;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-09T14:26:50+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PriceMapperImpl implements PriceMapper {

    @Override
    public PriceResponseDTO toResponseDTO(Price price) {
        if ( price == null ) {
            return null;
        }

        PriceResponseDTO.PriceResponseDTOBuilder priceResponseDTO = PriceResponseDTO.builder();

        priceResponseDTO.merchantId( priceMerchantId( price ) );
        priceResponseDTO.productId( priceProductId( price ) );
        priceResponseDTO.active( price.isActive() );
        priceResponseDTO.amount( price.getAmount() );
        priceResponseDTO.billingIntervalCount( price.getBillingIntervalCount() );
        priceResponseDTO.billingIntervalUnit( price.getBillingIntervalUnit() );
        priceResponseDTO.billingType( price.getBillingType() );
        priceResponseDTO.createdAt( price.getCreatedAt() );
        priceResponseDTO.currency( price.getCurrency() );
        priceResponseDTO.id( price.getId() );
        priceResponseDTO.priceCode( price.getPriceCode() );
        priceResponseDTO.trialDays( price.getTrialDays() );
        priceResponseDTO.updatedAt( price.getUpdatedAt() );

        return priceResponseDTO.build();
    }

    @Override
    public Price toEntity(PriceCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        Price.PriceBuilder price = Price.builder();

        price.amount( dto.getAmount() );
        if ( dto.getBillingIntervalCount() != null ) {
            price.billingIntervalCount( dto.getBillingIntervalCount() );
        }
        price.billingIntervalUnit( dto.getBillingIntervalUnit() );
        price.billingType( dto.getBillingType() );
        price.currency( dto.getCurrency() );
        price.priceCode( dto.getPriceCode() );
        price.trialDays( dto.getTrialDays() );

        return price.build();
    }

    @Override
    public void updateEntityFromDTO(PriceUpdateRequestDTO dto, Price entity) {
        if ( dto == null ) {
            return;
        }

        if ( dto.getTrialDays() != null ) {
            entity.setTrialDays( dto.getTrialDays() );
        }
    }

    private Long priceMerchantId(Price price) {
        if ( price == null ) {
            return null;
        }
        MerchantAccount merchant = price.getMerchant();
        if ( merchant == null ) {
            return null;
        }
        Long id = merchant.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long priceProductId(Price price) {
        if ( price == null ) {
            return null;
        }
        Product product = price.getProduct();
        if ( product == null ) {
            return null;
        }
        Long id = product.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
