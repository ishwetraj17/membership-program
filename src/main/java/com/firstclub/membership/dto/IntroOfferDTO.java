package com.firstclub.membership.dto;

import com.firstclub.membership.entity.IntroductoryOffer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntroOfferDTO {
    private Long id;
    private String code;
    private String description;
    private IntroductoryOffer.OfferType offerType;
    private BigDecimal value;
    private Long planId;
    private boolean active;
}
