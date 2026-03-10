package com.firstclub.merchant.mapper;

import com.firstclub.membership.entity.User;
import com.firstclub.merchant.dto.MerchantUserResponseDTO;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.entity.MerchantUser;
import javax.annotation.processing.Generated;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-10T18:17:14+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
public class MerchantUserMapperImpl implements MerchantUserMapper {

    @Override
    public MerchantUserResponseDTO toResponseDTO(MerchantUser merchantUser) {
        if ( merchantUser == null ) {
            return null;
        }

        MerchantUserResponseDTO.MerchantUserResponseDTOBuilder merchantUserResponseDTO = MerchantUserResponseDTO.builder();

        merchantUserResponseDTO.merchantId( merchantUserMerchantId( merchantUser ) );
        merchantUserResponseDTO.userId( merchantUserUserId( merchantUser ) );
        merchantUserResponseDTO.userEmail( merchantUserUserEmail( merchantUser ) );
        merchantUserResponseDTO.userName( merchantUserUserName( merchantUser ) );
        merchantUserResponseDTO.createdAt( merchantUser.getCreatedAt() );
        merchantUserResponseDTO.id( merchantUser.getId() );
        merchantUserResponseDTO.role( merchantUser.getRole() );

        return merchantUserResponseDTO.build();
    }

    private Long merchantUserMerchantId(MerchantUser merchantUser) {
        if ( merchantUser == null ) {
            return null;
        }
        MerchantAccount merchant = merchantUser.getMerchant();
        if ( merchant == null ) {
            return null;
        }
        Long id = merchant.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long merchantUserUserId(MerchantUser merchantUser) {
        if ( merchantUser == null ) {
            return null;
        }
        User user = merchantUser.getUser();
        if ( user == null ) {
            return null;
        }
        Long id = user.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private String merchantUserUserEmail(MerchantUser merchantUser) {
        if ( merchantUser == null ) {
            return null;
        }
        User user = merchantUser.getUser();
        if ( user == null ) {
            return null;
        }
        String email = user.getEmail();
        if ( email == null ) {
            return null;
        }
        return email;
    }

    private String merchantUserUserName(MerchantUser merchantUser) {
        if ( merchantUser == null ) {
            return null;
        }
        User user = merchantUser.getUser();
        if ( user == null ) {
            return null;
        }
        String name = user.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }
}
