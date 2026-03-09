package com.firstclub.payments.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * Request body for creating a mandate on a payment method.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodMandateCreateRequestDTO {

    /** Gateway-issued mandate registration reference. */
    @NotBlank(message = "mandateReference is required")
    @Size(max = 128)
    private String mandateReference;

    /** Maximum per-transaction debit amount authorised by this mandate. */
    @NotNull(message = "maxAmount is required")
    @DecimalMin(value = "0.0001", message = "maxAmount must be positive")
    private BigDecimal maxAmount;

    @NotBlank(message = "currency is required")
    @Size(max = 10)
    private String currency;
}
