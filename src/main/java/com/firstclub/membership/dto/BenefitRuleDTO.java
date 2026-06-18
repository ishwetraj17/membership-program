package com.firstclub.membership.dto;

import com.firstclub.membership.entity.BenefitType;
import com.firstclub.membership.entity.ProductCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenefitRuleDTO {
    private Long id;
    private Long tierId;
    private String tierName;
    private BenefitType benefitType;
    private ProductCategory productCategory;
    private BigDecimal minCartValue;
    private BigDecimal discountPercentage;
    private BigDecimal maxDiscountAmount;
    private int priority;
    private boolean active;
}
