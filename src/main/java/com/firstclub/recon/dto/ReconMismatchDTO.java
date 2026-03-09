package com.firstclub.recon.dto;

import com.firstclub.recon.classification.ReconExpectation;
import com.firstclub.recon.classification.ReconSeverity;
import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconMismatchStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class ReconMismatchDTO {
    Long                id;
    MismatchType        type;
    Long                invoiceId;
    Long                paymentId;
    String              details;
    ReconMismatchStatus status;
    Long                ownerUserId;
    String              resolutionNote;
    // Phase 14 taxonomy fields
    ReconExpectation    expectation;
    ReconSeverity       severity;
    String              gatewayTransactionId;
    Long                merchantId;
    String              currency;
    String              settlementCurrency;
    BigDecimal          fxRate;

    public static ReconMismatchDTO from(ReconMismatch m) {
        return ReconMismatchDTO.builder()
                .id(m.getId())
                .type(m.getType())
                .invoiceId(m.getInvoiceId())
                .paymentId(m.getPaymentId())
                .details(m.getDetails())
                .status(m.getStatus())
                .ownerUserId(m.getOwnerUserId())
                .resolutionNote(m.getResolutionNote())
                .expectation(m.getExpectation())
                .severity(m.getSeverity())
                .gatewayTransactionId(m.getGatewayTransactionId())
                .merchantId(m.getMerchantId())
                .currency(m.getCurrency())
                .settlementCurrency(m.getSettlementCurrency())
                .fxRate(m.getFxRate())
                .build();
    }
}

