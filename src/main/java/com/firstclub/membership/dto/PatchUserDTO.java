package com.firstclub.membership.dto;

import com.firstclub.membership.entity.User;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code PATCH /api/v1/users/{id}}.
 *
 * All fields are optional (nullable). Only non-null fields are applied to the
 * existing user record. Bean Validation constraints apply when a field is
 * present — null fields skip validation entirely.
 *
 * Email and password are intentionally excluded:
 *  - Email changes require a separate verified-email flow to prevent account takeover.
 *  - Password changes belong in a dedicated change-password endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatchUserDTO {

    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Please provide a valid Indian phone number (10 digits starting with 6-9)")
    private String phoneNumber;

    @Size(max = 255, message = "Address cannot exceed 255 characters")
    private String address;

    @Size(max = 100, message = "City name cannot exceed 100 characters")
    private String city;

    @Size(max = 100, message = "State name cannot exceed 100 characters")
    private String state;

    @Pattern(regexp = "^\\d{6}$", message = "Please provide a valid Indian pincode (6 digits)")
    private String pincode;

    private User.UserStatus status;
}
