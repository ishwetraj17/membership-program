package com.firstclub.membership.mapper;

import com.firstclub.membership.dto.UserDTO;
import com.firstclub.membership.entity.User;
import javax.annotation.processing.Generated;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-10T12:48:21+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
public class UserMapperImpl implements UserMapper {

    @Override
    public UserDTO toDTO(User user) {
        if ( user == null ) {
            return null;
        }

        UserDTO.UserDTOBuilder userDTO = UserDTO.builder();

        userDTO.address( user.getAddress() );
        userDTO.city( user.getCity() );
        userDTO.email( user.getEmail() );
        userDTO.id( user.getId() );
        userDTO.name( user.getName() );
        userDTO.phoneNumber( user.getPhoneNumber() );
        userDTO.pincode( user.getPincode() );
        userDTO.state( user.getState() );
        userDTO.status( user.getStatus() );

        return userDTO.build();
    }

    @Override
    public User toEntity(UserDTO dto) {
        if ( dto == null ) {
            return null;
        }

        User.UserBuilder user = User.builder();

        user.address( dto.getAddress() );
        user.city( dto.getCity() );
        user.email( dto.getEmail() );
        user.name( dto.getName() );
        user.phoneNumber( dto.getPhoneNumber() );
        user.pincode( dto.getPincode() );
        user.state( dto.getState() );
        user.status( dto.getStatus() );

        return user.build();
    }

    @Override
    public void updateEntityFromDTO(UserDTO dto, User user) {
        if ( dto == null ) {
            return;
        }

        user.setAddress( dto.getAddress() );
        user.setCity( dto.getCity() );
        user.setEmail( dto.getEmail() );
        user.setName( dto.getName() );
        user.setPhoneNumber( dto.getPhoneNumber() );
        user.setPincode( dto.getPincode() );
        user.setState( dto.getState() );
        user.setStatus( dto.getStatus() );
    }
}
