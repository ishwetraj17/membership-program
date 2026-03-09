package com.firstclub.merchant.auth.dto;

import com.firstclub.merchant.auth.entity.MerchantApiKeyMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MerchantApiKeyCreateRequestDTO {

    @NotNull
    private MerchantApiKeyMode mode;

    /**
     * List of access scopes for this key.
     * Use constants from {@link com.firstclub.merchant.auth.ApiScope}.
     * Example: ["customers:read", "payments:write"]
     */
    @NotNull
    @NotEmpty
    private List<String> scopes;
}
