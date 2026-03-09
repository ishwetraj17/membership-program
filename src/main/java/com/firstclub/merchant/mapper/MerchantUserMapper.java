package com.firstclub.merchant.mapper;

import com.firstclub.merchant.dto.MerchantUserResponseDTO;
import com.firstclub.merchant.entity.MerchantUser;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link MerchantUser} → {@link MerchantUserResponseDTO}.
 */
@Mapper
public interface MerchantUserMapper {

    @Mapping(target = "merchantId", source = "merchant.id")
    @Mapping(target = "userId",     source = "user.id")
    @Mapping(target = "userEmail",  source = "user.email")
    @Mapping(target = "userName",   source = "user.name")
    MerchantUserResponseDTO toResponseDTO(MerchantUser merchantUser);
}
