package com.firstclub.payments.disputes.dto;

import com.firstclub.payments.disputes.entity.DisputeStatus;
import com.firstclub.payments.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Dispute response returned by all read and write endpoints. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeResponseDTO {

    private Long          id;
    private Long          merchantId;
    private Long          paymentId;
    private Long          customerId;
    private BigDecimal    amount;
    private String        reasonCode;
    private DisputeStatus status;
    private LocalDateTime openedAt;
    private LocalDateTime dueBy;
    private LocalDateTime resolvedAt;

    /** Snapshot of the parent payment's status at the time of this response. */
    private PaymentStatus paymentStatusAfter;

    /** True once the DISPUTE_RESERVE accounting entry has been posted (Phase 15). */
    private boolean reservePosted;

    /** True once the WON or LOST resolution accounting entry has been posted (Phase 15). */
    private boolean resolutionPosted;
}
