package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for User <-> UserDTO conversions.
 * Replaces the manual convertToDTO() in UserServiceImpl.
 * Spring component model is set globally via maven-compiler-plugin arg.
 */
@Mapper
public interface UserMapper {

    /**
     * Entity → DTO. Excludes password (never returned in responses).
     */
    @Mapping(target = "password", ignore = true)
    UserDTO toDTO(User user);

    /**
     * DTO → new Entity (for create operations).
     * id, createdAt, updatedAt, subscriptions, isDeleted are managed
     * by the persistence layer and must not be set from DTO.
     */
    @Mapping(target = "id",            ignore = true)
    @Mapping(target = "password",      ignore = true)
    @Mapping(target = "roles",         ignore = true)
    @Mapping(target = "isDeleted",     ignore = true)
    @Mapping(target = "subscriptions", ignore = true)
    @Mapping(target = "createdAt",     ignore = true)
    @Mapping(target = "updatedAt",     ignore = true)
    User toEntity(UserDTO dto);

    /**
     * Apply DTO fields to an existing entity (for update operations).
     * Preserves id, password, roles, isDeleted, subscriptions, and timestamps.
     */
    @Mapping(target = "id",            ignore = true)
    @Mapping(target = "password",      ignore = true)
    @Mapping(target = "roles",         ignore = true)
    @Mapping(target = "isDeleted",     ignore = true)
    @Mapping(target = "subscriptions", ignore = true)
    @Mapping(target = "createdAt",     ignore = true)
    @Mapping(target = "updatedAt",     ignore = true)
    void updateEntityFromDTO(UserDTO dto, @MappingTarget User user);
}
