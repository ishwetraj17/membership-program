package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Self-service registration — creates a membership user and a linked USER login in one step.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Register a new member (creates a membership user + login)")
public class RegisterRequest {

    @NotBlank @Size(min = 2, max = 100)
    private String name;

    @NotBlank @Email
    private String email;

    @NotBlank @Size(min = 8, max = 100)
    @Schema(description = "Login password (min 8 chars)")
    private String password;

    @NotBlank
    @Pattern(regexp = "^[6-9]\\d{9}$", message = "Please provide a valid Indian phone number")
    private String phoneNumber;

    @NotBlank @Size(max = 255)
    private String address;

    @NotBlank @Size(max = 100)
    private String city;

    @NotBlank @Size(max = 100)
    private String state;

    @NotBlank
    @Pattern(regexp = "^\\d{6}$", message = "Please provide a valid Indian pincode")
    private String pincode;
}
