package com.firstclub.customer.mapper;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.customer.dto.CustomerUpdateRequestDTO;
import com.firstclub.customer.entity.Customer;
import com.firstclub.merchant.entity.MerchantAccount;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-09T09:53:54+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class CustomerMapperImpl implements CustomerMapper {

    @Override
    public CustomerResponseDTO toResponseDTO(Customer customer) {
        if ( customer == null ) {
            return null;
        }

        CustomerResponseDTO.CustomerResponseDTOBuilder customerResponseDTO = CustomerResponseDTO.builder();

        customerResponseDTO.merchantId( customerMerchantId( customer ) );
        customerResponseDTO.billingAddress( customer.getBillingAddress() );
        customerResponseDTO.createdAt( customer.getCreatedAt() );
        customerResponseDTO.defaultPaymentMethodId( customer.getDefaultPaymentMethodId() );
        customerResponseDTO.email( customer.getEmail() );
        customerResponseDTO.externalCustomerId( customer.getExternalCustomerId() );
        customerResponseDTO.fullName( customer.getFullName() );
        customerResponseDTO.id( customer.getId() );
        customerResponseDTO.metadataJson( customer.getMetadataJson() );
        customerResponseDTO.phone( customer.getPhone() );
        customerResponseDTO.shippingAddress( customer.getShippingAddress() );
        customerResponseDTO.status( customer.getStatus() );
        customerResponseDTO.updatedAt( customer.getUpdatedAt() );

        return customerResponseDTO.build();
    }

    @Override
    public Customer toEntity(CustomerCreateRequestDTO dto) {
        if ( dto == null ) {
            return null;
        }

        Customer.CustomerBuilder customer = Customer.builder();

        customer.billingAddress( dto.getBillingAddress() );
        customer.email( dto.getEmail() );
        customer.externalCustomerId( dto.getExternalCustomerId() );
        customer.fullName( dto.getFullName() );
        customer.metadataJson( dto.getMetadataJson() );
        customer.phone( dto.getPhone() );
        customer.shippingAddress( dto.getShippingAddress() );

        return customer.build();
    }

    @Override
    public void updateEntityFromDTO(CustomerUpdateRequestDTO dto, Customer entity) {
        if ( dto == null ) {
            return;
        }

        if ( dto.getBillingAddress() != null ) {
            entity.setBillingAddress( dto.getBillingAddress() );
        }
        if ( dto.getEmail() != null ) {
            entity.setEmail( dto.getEmail() );
        }
        if ( dto.getExternalCustomerId() != null ) {
            entity.setExternalCustomerId( dto.getExternalCustomerId() );
        }
        if ( dto.getFullName() != null ) {
            entity.setFullName( dto.getFullName() );
        }
        if ( dto.getMetadataJson() != null ) {
            entity.setMetadataJson( dto.getMetadataJson() );
        }
        if ( dto.getPhone() != null ) {
            entity.setPhone( dto.getPhone() );
        }
        if ( dto.getShippingAddress() != null ) {
            entity.setShippingAddress( dto.getShippingAddress() );
        }
    }

    private Long customerMerchantId(Customer customer) {
        if ( customer == null ) {
            return null;
        }
        MerchantAccount merchant = customer.getMerchant();
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
