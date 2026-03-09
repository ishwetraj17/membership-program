package com.firstclub.customer.mapper;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.customer.dto.CustomerUpdateRequestDTO;
import com.firstclub.customer.entity.Customer;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * MapStruct mapper for {@link Customer} ↔ DTOs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CustomerMapper {

    /** Entity → response DTO; merchant.id is extracted from the association. */
    @Mapping(target = "merchantId", source = "merchant.id")
    CustomerResponseDTO toResponseDTO(Customer customer);

    /**
     * Create request → entity (partial; merchant association is set by the service).
     * Email is normalised to lower-case by the service before mapping.
     */
    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "merchant",    ignore = true)
    @Mapping(target = "status",      ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    @Mapping(target = "notes",       ignore = true)
    @Mapping(target = "defaultPaymentMethodId", ignore = true)
    Customer toEntity(CustomerCreateRequestDTO dto);

    /**
     * Applies non-null update fields onto an existing entity (patch semantics).
     * Immutable and system-managed fields are protected.
     */
    @Mapping(target = "id",          ignore = true)
    @Mapping(target = "merchant",    ignore = true)
    @Mapping(target = "status",      ignore = true)
    @Mapping(target = "createdAt",   ignore = true)
    @Mapping(target = "updatedAt",   ignore = true)
    @Mapping(target = "notes",       ignore = true)
    @Mapping(target = "defaultPaymentMethodId", ignore = true)
    void updateEntityFromDTO(CustomerUpdateRequestDTO dto, @MappingTarget Customer entity);
}
