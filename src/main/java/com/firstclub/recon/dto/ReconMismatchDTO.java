package com.firstclub.recon.dto;

import com.firstclub.recon.entity.MismatchType;
import com.firstclub.recon.entity.ReconMismatch;
import com.firstclub.recon.entity.ReconMismatchStatus;
import lombok.Builder;
import lombok.Value;

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
                .build();
    }
}
