package com.firstclub.membership.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO for updating subscription settings
 * 
 * Currently only supports auto-renewal toggle.
 * TODO: Add more update options like payment method changes
 * 
 * Implemented by Shwet Raj
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionUpdateDTO {

    private Boolean autoRenewal;
}