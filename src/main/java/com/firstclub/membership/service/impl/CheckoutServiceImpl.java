package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.CheckoutQuoteRequest;
import com.firstclub.membership.dto.CheckoutQuoteResponse;
import com.firstclub.membership.dto.QuoteLineItem;
import com.firstclub.membership.dto.OrderDTO;
import com.firstclub.membership.dto.SubscriptionDTO;
import com.firstclub.membership.entity.FeeType;
import com.firstclub.membership.entity.Order;
import com.firstclub.membership.entity.ProductCategory;
import com.firstclub.membership.repository.OrderRepository;
import com.firstclub.membership.service.BenefitEngine;
import com.firstclub.membership.service.CheckoutService;
import com.firstclub.membership.service.CouponService;
import com.firstclub.membership.service.SavingsService;
import com.firstclub.membership.service.SubscriptionService;
import com.firstclub.membership.service.benefit.BenefitEvaluation;
import com.firstclub.membership.service.benefit.CartContext;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CheckoutServiceImpl implements CheckoutService {

    private final SubscriptionService subscriptionService;
    private final CouponService couponService;
    private final BenefitEngine benefitEngine;
    private final SavingsService savingsService;
    private final OrderRepository orderRepository;
    private final Clock clock;

    /** A priced cart plus the structured evaluation behind it (for order persistence + savings). */
    private record Priced(CheckoutQuoteResponse response, BenefitEvaluation evaluation) {}

    @Override
    @Transactional
    public OrderDTO confirm(CheckoutQuoteRequest request) {
        // Compute the priced cart (includes coupon validation/preview), persist the order, then
        // redeem the coupon tied to that order — all in one transaction, so a redemption never
        // exists without its order and a limit breach rolls the whole order back.
        Priced priced = price(request);
        CheckoutQuoteResponse quote = priced.response();

        Order order = orderRepository.save(Order.builder()
                .userId(request.getUserId())
                .subtotal(quote.getSubtotal())
                .memberDiscount(quote.getDiscountAmount())
                .couponCode(quote.getCouponCode())
                .couponDiscount(quote.getCouponDiscount() != null ? quote.getCouponDiscount() : BigDecimal.ZERO)
                .deliveryFee(quote.getDeliveryFee())
                .handlingFee(quote.getHandlingFee())
                .smallCartFee(quote.getSmallCartFee())
                .surgeFee(quote.getSurgeFee())
                .rainFee(quote.getRainFee())
                .total(quote.getTotal())
                .status(Order.Status.PLACED)
                .placedAt(LocalDateTime.now(clock))
                .build());

        if (quote.getCouponCode() != null) {
            BigDecimal netGoods = quote.getSubtotal().subtract(quote.getDiscountAmount());
            couponService.redeem(quote.getCouponCode(), request.getUserId(), order.getId(), netGoods);
        }

        // Record realised savings (discounts, waived fees, coupon) against the placed order — in the
        // same transaction, so the auditable ledger never diverges from the orders it explains.
        savingsService.recordOrderSavings(request.getUserId(), order.getId(), priced.evaluation(),
                order.getCouponDiscount(), order.getPlacedAt());

        return OrderDTO.builder()
                .orderId(order.getId()).userId(order.getUserId())
                .subtotal(order.getSubtotal()).memberDiscount(order.getMemberDiscount())
                .couponCode(order.getCouponCode()).couponDiscount(order.getCouponDiscount())
                .deliveryFee(order.getDeliveryFee())
                .handlingFee(order.getHandlingFee()).smallCartFee(order.getSmallCartFee())
                .surgeFee(order.getSurgeFee()).rainFee(order.getRainFee())
                .total(order.getTotal())
                .status(order.getStatus()).placedAt(order.getPlacedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutQuoteResponse quote(CheckoutQuoteRequest request) {
        return price(request).response();
    }

    private Priced price(CheckoutQuoteRequest request) {
        BigDecimal subtotal = request.getItems().stream()
                .map(this::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        Map<ProductCategory, BigDecimal> categorySubtotals = categorySubtotals(request.getItems());
        Map<FeeType, BigDecimal> fees = fees(request);

        // Benefits come from the user's current ACTIVE subscription tier (if any); the engine
        // evaluates that tier's configured rules against the cart.
        Optional<SubscriptionDTO> active = subscriptionService.getActiveSubscription(request.getUserId());

        List<String> applied = new ArrayList<>();
        BigDecimal discountPercentage = BigDecimal.ZERO;
        String tier = null;
        Integer coupons = null;
        BenefitEvaluation evaluation;

        if (active.isPresent() && active.get().getTierId() != null) {
            SubscriptionDTO sub = active.get();
            tier = sub.getTier();
            coupons = sub.getMaxCouponsPerMonth();
            discountPercentage = sub.getDiscountPercentage() != null ? sub.getDiscountPercentage() : BigDecimal.ZERO;

            CartContext cart = CartContext.builder()
                    .subtotal(subtotal).categorySubtotals(categorySubtotals).fees(fees).build();
            evaluation = benefitEngine.evaluate(sub.getTierId(), cart);
            applied.addAll(evaluation.getAppliedBenefits());
            if (coupons != null && coupons > 0) applied.add(coupons + " coupons/month");
        } else {
            evaluation = BenefitEvaluation.none(fees);
        }

        BigDecimal discountAmount = evaluation.getDiscountAmount();
        boolean deliveryWaived = evaluation.isWaived(FeeType.DELIVERY);

        // Coupon (preview only — redemption happens at confirm) applies to the post-member-discount
        // goods total, exactly as before.
        BigDecimal netGoods = subtotal.subtract(discountAmount);
        BigDecimal couponDiscount = BigDecimal.ZERO;
        String couponCode = null;
        if (StringUtils.hasText(request.getCouponCode())) {
            couponCode = request.getCouponCode().toUpperCase();
            couponDiscount = couponService.previewDiscount(couponCode, request.getUserId(), netGoods);
            applied.add("Coupon " + couponCode + " (−" + couponDiscount + ")");
        }

        BigDecimal totalFees = evaluation.totalChargedFees();
        BigDecimal total = netGoods.subtract(couponDiscount).add(totalFees).setScale(2, RoundingMode.HALF_UP);

        List<String> waivedFees = evaluation.getWaivedFees().stream().map(Enum::name).sorted().toList();

        CheckoutQuoteResponse response = CheckoutQuoteResponse.builder()
                .userId(request.getUserId())
                .membershipTier(tier)
                .subtotal(subtotal)
                .discountPercentage(discountPercentage)
                .discountAmount(discountAmount)
                .deliveryFee(evaluation.chargedFee(FeeType.DELIVERY))
                .deliveryWaived(deliveryWaived)
                .handlingFee(evaluation.chargedFee(FeeType.HANDLING))
                .smallCartFee(evaluation.chargedFee(FeeType.SMALL_CART))
                .surgeFee(evaluation.chargedFee(FeeType.SURGE))
                .rainFee(evaluation.chargedFee(FeeType.RAIN))
                .totalFees(totalFees)
                .waivedFees(waivedFees)
                .couponCode(couponCode)
                .couponDiscount(couponDiscount)
                .total(total)
                .appliedBenefits(applied)
                .couponsAvailable(coupons)
                .build();
        return new Priced(response, evaluation);
    }

    private Map<ProductCategory, BigDecimal> categorySubtotals(List<QuoteLineItem> items) {
        Map<ProductCategory, BigDecimal> byCategory = new EnumMap<>(ProductCategory.class);
        for (QuoteLineItem item : items) {
            ProductCategory.from(item.getCategory()).ifPresent(category ->
                    byCategory.merge(category, lineTotal(item), BigDecimal::add));
        }
        return byCategory;
    }

    private Map<FeeType, BigDecimal> fees(CheckoutQuoteRequest request) {
        Map<FeeType, BigDecimal> fees = new EnumMap<>(FeeType.class);
        fees.put(FeeType.DELIVERY, scaled(request.getDeliveryFee()));
        fees.put(FeeType.HANDLING, scaled(request.getHandlingFee()));
        fees.put(FeeType.SMALL_CART, scaled(request.getSmallCartFee()));
        fees.put(FeeType.SURGE, scaled(request.getSurgeFee()));
        fees.put(FeeType.RAIN, scaled(request.getRainFee()));
        return fees;
    }

    private BigDecimal scaled(BigDecimal fee) {
        return fee != null ? fee.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private BigDecimal lineTotal(QuoteLineItem item) {
        return item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
    }
}
