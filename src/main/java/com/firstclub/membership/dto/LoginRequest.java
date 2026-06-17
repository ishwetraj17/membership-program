package com.firstclub.membership.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Credentials for obtaining a JWT")
public class LoginRequest {

    @NotBlank(message = "Username is required")
    @Schema(example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @NotBlank(message = "Password is required")
    @Schema(example = "admin123", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
