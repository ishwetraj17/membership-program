package com.firstclub.membership.dto;

import com.firstclub.membership.entity.User;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.*;

/**
 * Data Transfer Object for User information
 * 
 * Used for API requests/responses to avoid exposing entity details.
 * Includes validation for Indian phone numbers and pincodes.
 * 
 * Implemented by Shwet Raj
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    private Long id;

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Please provide a valid email address")
    private String email;

    // Indian phone number validation - 10 digits starting with 6-9
    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Please provide a valid Indian phone number (10 digits starting with 6-9)")
    private String phoneNumber;

    @NotBlank(message = "Address is required")
    @Size(max = 255, message = "Address cannot exceed 255 characters")
    private String address;

    @NotBlank(message = "City is required")
    @Size(max = 100, message = "City name cannot exceed 100 characters")
    private String city;

    @NotBlank(message = "State is required")
    @Size(max = 100, message = "State name cannot exceed 100 characters")
    private String state;

    // Indian pincode validation - 6 digits
    @NotBlank(message = "Pincode is required")
    @Pattern(regexp = "^\\d{6}$", message = "Please provide a valid Indian pincode (6 digits)")
    private String pincode;

    private User.UserStatus status;
}