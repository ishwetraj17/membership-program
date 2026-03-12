package com.firstclub.customer.mapper;

import com.firstclub.customer.dto.CustomerNoteResponseDTO;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerNote;
import com.firstclub.membership.entity.User;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-12T15:43:33+0530",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class CustomerNoteMapperImpl implements CustomerNoteMapper {

    @Override
    public CustomerNoteResponseDTO toResponseDTO(CustomerNote note) {
        if ( note == null ) {
            return null;
        }

        CustomerNoteResponseDTO.CustomerNoteResponseDTOBuilder customerNoteResponseDTO = CustomerNoteResponseDTO.builder();

        customerNoteResponseDTO.customerId( noteCustomerId( note ) );
        customerNoteResponseDTO.authorUserId( noteAuthorId( note ) );
        customerNoteResponseDTO.authorName( noteAuthorName( note ) );
        customerNoteResponseDTO.createdAt( note.getCreatedAt() );
        customerNoteResponseDTO.id( note.getId() );
        customerNoteResponseDTO.noteText( note.getNoteText() );
        customerNoteResponseDTO.visibility( note.getVisibility() );

        return customerNoteResponseDTO.build();
    }

    private Long noteCustomerId(CustomerNote customerNote) {
        if ( customerNote == null ) {
            return null;
        }
        Customer customer = customerNote.getCustomer();
        if ( customer == null ) {
            return null;
        }
        Long id = customer.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private Long noteAuthorId(CustomerNote customerNote) {
        if ( customerNote == null ) {
            return null;
        }
        User author = customerNote.getAuthor();
        if ( author == null ) {
            return null;
        }
        Long id = author.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }

    private String noteAuthorName(CustomerNote customerNote) {
        if ( customerNote == null ) {
            return null;
        }
        User author = customerNote.getAuthor();
        if ( author == null ) {
            return null;
        }
        String name = author.getName();
        if ( name == null ) {
            return null;
        }
        return name;
    }
}
