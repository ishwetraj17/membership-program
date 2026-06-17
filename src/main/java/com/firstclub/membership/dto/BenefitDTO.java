package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Benefit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenefitDTO {

    private String code;
    private String name;
    private String description;
    private Benefit.Category category;
    /** Optional quantitative value at the tier (e.g. "10%", "5/month"). */
    private String value;
}
