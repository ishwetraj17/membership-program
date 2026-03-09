package com.firstclub.recon.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

@Value
@Builder
public class SettlementDTO {
    Long       id;
    LocalDate  settlementDate;
    BigDecimal totalAmount;
    String     currency;
}
