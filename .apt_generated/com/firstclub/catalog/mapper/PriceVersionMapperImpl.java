package com.firstclub.catalog.mapper;

import com.firstclub.catalog.dto.PriceVersionCreateRequestDTO;
import com.firstclub.catalog.dto.PriceVersionResponseDTO;
import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.PriceVersion;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-10T19:07:05+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class PriceVersionMapperImpl implements PriceVersionMapper {

    @Override
    public PriceVersionResponseDTO toResponseDTO(PriceVersion version) {
        if ( version == null ) {
            return null;
        }

        PriceVersionResponseDTO.PriceVersionResponseDTOBuilder priceVersionResponseDTO = PriceVersionResponseDTO.builder();

        priceVersionResponseDTO.priceId( versionPriceId( version ) );
        priceVersionResponseDTO.amount( version.getAmount() );
        priceVersionResponseDTO.createdAt( version.getCreatedAt() );
        priceVersionResponseDTO.currency( version.getCurrency() );
        priceVersionResponseDTO.effectiveFrom( version.getEffectiveFrom() );
        priceVersionResponseDTO.effectiveTo( version.getEffectiveTo() );
        priceVersionResponseDTO.grandfatherExistingSubscriptions( version.isGrandfatherExistingSubscriptions() );
        priceVersionResponseDTO.id( version.getId() );

        return priceVersionResponseDTO.build();
    }

    @Override
    public PriceVersion toEntity(PriceVersionCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        PriceVersion.PriceVersionBuilder priceVersion = PriceVersion.builder();

        priceVersion.amount( dto.getAmount() );
        priceVersion.currency( dto.getCurrency() );
        priceVersion.effectiveFrom( dto.getEffectiveFrom() );
        priceVersion.grandfatherExistingSubscriptions( dto.isGrandfatherExistingSubscriptions() );

        return priceVersion.build();
    }

    private Long versionPriceId(PriceVersion priceVersion) {
        if ( priceVersion == null ) {
            return null;
        }
        Price price = priceVersion.getPrice();
        if ( price == null ) {
            return null;
        }
        Long id = price.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
