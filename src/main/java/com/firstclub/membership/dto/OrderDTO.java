package com.firstclub.membership.dto;

import com.firstclub.membership.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDTO {
    private Long orderId;
    private Long userId;
    private BigDecimal subtotal;
    private BigDecimal memberDiscount;
    private String couponCode;
    private BigDecimal couponDiscount;
    private BigDecimal deliveryFee;
    private BigDecimal total;
    private Order.Status status;
    private LocalDateTime placedAt;
}
