package com.firstclub.membership.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemCouponResponse {
    private String code;
    private BigDecimal orderAmount;
    private BigDecimal discountAmount;
    private BigDecimal payable;
}
