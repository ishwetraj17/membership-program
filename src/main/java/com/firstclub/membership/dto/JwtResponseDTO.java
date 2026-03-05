package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * JWT authentication response returned after a successful login.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtResponseDTO {

    private String token;

    @Builder.Default
    private String type = "Bearer";

    private Long userId;
    private String email;
    private String name;
    private Set<String> roles;
}
