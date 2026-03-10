package com.firstclub.catalog.mapper;

import com.firstclub.catalog.dto.ProductCreateRequestDTO;
import com.firstclub.catalog.dto.ProductResponseDTO;
import com.firstclub.catalog.dto.ProductUpdateRequestDTO;
import com.firstclub.catalog.entity.Product;
import com.firstclub.merchant.entity.MerchantAccount;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-10T19:07:05+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ProductMapperImpl implements ProductMapper {

    @Override
    public ProductResponseDTO toResponseDTO(Product product) {
        if ( product == null ) {
            return null;
        }

        ProductResponseDTO.ProductResponseDTOBuilder productResponseDTO = ProductResponseDTO.builder();

        productResponseDTO.merchantId( productMerchantId( product ) );
        productResponseDTO.createdAt( product.getCreatedAt() );
        productResponseDTO.description( product.getDescription() );
        productResponseDTO.id( product.getId() );
        productResponseDTO.name( product.getName() );
        productResponseDTO.productCode( product.getProductCode() );
        productResponseDTO.status( product.getStatus() );
        productResponseDTO.updatedAt( product.getUpdatedAt() );

        return productResponseDTO.build();
    }

    @Override
    public Product toEntity(ProductCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        Product.ProductBuilder product = Product.builder();

        product.description( dto.getDescription() );
        product.name( dto.getName() );
        product.productCode( dto.getProductCode() );

        return product.build();
    }

    @Override
    public void updateEntityFromDTO(ProductUpdateRequestDTO dto, Product entity) {
        if ( dto == null ) {
            return;
        }

        if ( dto.getDescription() != null ) {
            entity.setDescription( dto.getDescription() );
        }
        if ( dto.getName() != null ) {
            entity.setName( dto.getName() );
        }
    }

    private Long productMerchantId(Product product) {
        if ( product == null ) {
            return null;
        }
        MerchantAccount merchant = product.getMerchant();
        if ( merchant == null ) {
            return null;
        }
        Long id = merchant.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
