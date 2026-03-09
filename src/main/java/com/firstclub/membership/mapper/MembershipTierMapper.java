package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.MembershipTierDTO;
import com.firstclub.membership.entity.MembershipTier;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for MembershipTier <-> MembershipTierDTO.
 * Stops the controller from returning entity objects directly.
 *
 * All field names match between entity and DTO so MapStruct maps them
 * automatically — no explicit @Mapping annotations are needed.
 */
@Mapper
public interface MembershipTierMapper {

    /**
     * Entity → DTO. Excludes the plans collection (lazy-loaded, not needed in responses).
     */
    MembershipTierDTO toDTO(MembershipTier tier);
}
