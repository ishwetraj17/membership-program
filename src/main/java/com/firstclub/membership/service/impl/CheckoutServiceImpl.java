package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.CheckoutQuoteRequest;
import com.firstclub.membership.dto.CheckoutQuoteResponse;
import com.firstclub.membership.dto.QuoteLineItem;
import com.firstclub.membership.dto.OrderDTO;
import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.entity.Order;
import com.firstclub.membership.repository.OrderRepository;
import com.firstclub.membership.service.CheckoutService;
import com.firstclub.membership.service.CouponService;
import com.firstclub.membership.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CheckoutServiceImpl implements CheckoutService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SubscriptionService subscriptionService;
    private final CouponService couponService;
    private final OrderRepository orderRepository;
    private final Clock clock;

    @Override
    @Transactional
    public OrderDTO confirm(CheckoutQuoteRequest request) {
        // Compute the priced cart (includes coupon validation/preview), persist the order, then
        // redeem the coupon tied to that order — all in one transaction, so a redemption never
        // exists without its order and a limit breach rolls the whole order back.
        CheckoutQuoteResponse quote = quote(request);

        Order order = orderRepository.save(Order.builder()
                .userId(request.getUserId())
                .subtotal(quote.getSubtotal())
                .memberDiscount(quote.getDiscountAmount())
                .couponCode(quote.getCouponCode())
                .couponDiscount(quote.getCouponDiscount() != null ? quote.getCouponDiscount() : BigDecimal.ZERO)
                .deliveryFee(quote.isDeliveryWaived() ? BigDecimal.ZERO : quote.getDeliveryFee())
                .total(quote.getTotal())
                .status(Order.Status.PLACED)
                .placedAt(LocalDateTime.now(clock))
                .build());

        if (quote.getCouponCode() != null) {
            BigDecimal netGoods = quote.getSubtotal().subtract(quote.getDiscountAmount());
            couponService.redeem(quote.getCouponCode(), request.getUserId(), order.getId(), netGoods);
        }

        return OrderDTO.builder()
                .orderId(order.getId()).userId(order.getUserId())
                .subtotal(order.getSubtotal()).memberDiscount(order.getMemberDiscount())
                .couponCode(order.getCouponCode()).couponDiscount(order.getCouponDiscount())
                .deliveryFee(order.getDeliveryFee()).total(order.getTotal())
                .status(order.getStatus()).placedAt(order.getPlacedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutQuoteResponse quote(CheckoutQuoteRequest request) {
        BigDecimal subtotal = request.getItems().stream()
                .map(this::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal deliveryFee = request.getDeliveryFee() != null
                ? request.getDeliveryFee().setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Benefits come from the user's current ACTIVE subscription tier (if any).
        Optional<SubscriptionDTO> active = subscriptionService.getActiveSubscription(request.getUserId());

        List<String> applied = new ArrayList<>();
        BigDecimal discountPercentage = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;
        boolean deliveryWaived = false;
        String tier = null;
        Integer coupons = null;

        if (active.isPresent()) {
            SubscriptionDTO sub = active.get();
            tier = sub.getTier();
            coupons = sub.getMaxCouponsPerMonth();

            discountPercentage = sub.getDiscountPercentage() != null ? sub.getDiscountPercentage() : BigDecimal.ZERO;
            if (discountPercentage.signum() > 0) {
                discountAmount = subtotal.multiply(discountPercentage)
                        .divide(HUNDRED, 2, RoundingMode.HALF_UP);
                applied.add(discountPercentage.stripTrailingZeros().toPlainString() + "% member discount");
            }

            if (Boolean.TRUE.equals(sub.getFreeDelivery())) {
                deliveryWaived = true;
                applied.add("Free delivery");
            }
            if (Boolean.TRUE.equals(sub.getEarlyAccess())) applied.add("Early sale access");
            if (Boolean.TRUE.equals(sub.getExclusiveDeals())) applied.add("Exclusive deals");
            if (coupons != null && coupons > 0) applied.add(coupons + " coupons/month");
        }

        // Coupon (preview only — redemption happens via POST /coupons/redeem) applies to the
        // post-member-discount goods total.
        BigDecimal netGoods = subtotal.subtract(discountAmount);
        BigDecimal couponDiscount = BigDecimal.ZERO;
        String couponCode = null;
        if (StringUtils.hasText(request.getCouponCode())) {
            couponCode = request.getCouponCode().toUpperCase();
            couponDiscount = couponService.previewDiscount(couponCode, request.getUserId(), netGoods);
            applied.add("Coupon " + couponCode + " (−" + couponDiscount + ")");
        }

        BigDecimal effectiveDelivery = deliveryWaived ? BigDecimal.ZERO : deliveryFee;
        BigDecimal total = netGoods.subtract(couponDiscount).add(effectiveDelivery).setScale(2, RoundingMode.HALF_UP);

        return CheckoutQuoteResponse.builder()
                .userId(request.getUserId())
                .membershipTier(tier)
                .subtotal(subtotal)
                .discountPercentage(discountPercentage)
                .discountAmount(discountAmount)
                .deliveryFee(deliveryFee)
                .deliveryWaived(deliveryWaived)
                .couponCode(couponCode)
                .couponDiscount(couponDiscount)
                .total(total)
                .appliedBenefits(applied)
                .couponsAvailable(coupons)
                .build();
    }

    private BigDecimal lineTotal(QuoteLineItem item) {
        return item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
    }
}
