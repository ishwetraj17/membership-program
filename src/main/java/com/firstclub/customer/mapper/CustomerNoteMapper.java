package com.firstclub.customer.mapper;

import com.firstclub.customer.dto.CustomerNoteResponseDTO;
import com.firstclub.customer.entity.CustomerNote;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for {@link CustomerNote} → {@link CustomerNoteResponseDTO}.
 */
@Mapper(componentModel = "spring")
public interface CustomerNoteMapper {

    @Mapping(target = "customerId",    source = "customer.id")
    @Mapping(target = "authorUserId",  source = "author.id")
    @Mapping(target = "authorName",    source = "author.name")
    CustomerNoteResponseDTO toResponseDTO(CustomerNote note);
}
