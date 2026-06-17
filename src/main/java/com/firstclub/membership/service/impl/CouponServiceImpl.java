package com.firstclub.membership.service.impl;

import com.firstclub.membership.dto.CouponDTO;
import com.firstclub.membership.dto.CreateCouponRequest;
import com.firstclub.membership.dto.RedeemCouponResponse;
import com.firstclub.membership.entity.Coupon;
import com.firstclub.membership.entity.CouponRedemption;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.membership.repository.CouponRedemptionRepository;
import com.firstclub.membership.repository.CouponRepository;
import com.firstclub.membership.service.AuditService;
import com.firstclub.membership.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Override
    @Transactional
    public CouponDTO createCoupon(CreateCouponRequest request) {
        String code = request.getCode().toUpperCase();
        if (couponRepository.existsByCode(code)) {
            throw new MembershipException("Coupon '" + code + "' already exists", "COUPON_EXISTS", HttpStatus.CONFLICT);
        }
        Coupon saved = couponRepository.save(Coupon.builder()
                .code(code)
                .description(request.getDescription())
                .discountType(request.getDiscountType())
                .discountValue(request.getDiscountValue())
                .maxRedemptions(request.getMaxRedemptions())
                .perUserLimit(request.getPerUserLimit())
                .active(true)
                .expiresAt(request.getExpiresAt())
                .build());
        auditService.record("COUPON_CREATED", saved.getCode());
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<CouponDTO> listCoupons() {
        return couponRepository.findAll().stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public CouponDTO deactivateCoupon(String code) {
        Coupon coupon = couponRepository.findByCode(code.toUpperCase())
                .orElseThrow(() -> couponNotFound(code));
        coupon.setActive(false);
        return toDto(couponRepository.save(coupon));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal previewDiscount(String code, Long userId, BigDecimal amount) {
        Coupon coupon = couponRepository.findByCode(code.toUpperCase())
                .orElseThrow(() -> couponNotFound(code));
        checkUsable(coupon, userId);
        return discountFor(coupon, amount);
    }

    @Override
    @Transactional
    public RedeemCouponResponse redeem(String code, Long userId, BigDecimal orderAmount) {
        return redeem(code, userId, null, orderAmount);
    }

    @Override
    @Transactional
    public RedeemCouponResponse redeem(String code, Long userId, Long orderId, BigDecimal orderAmount) {
        // Lock the coupon row so concurrent redemptions can't bypass the limits (count-then-insert race).
        Coupon coupon = couponRepository.findByCodeForUpdate(code.toUpperCase())
                .orElseThrow(() -> couponNotFound(code));
        checkUsable(coupon, userId);
        BigDecimal discount = discountFor(coupon, orderAmount);

        redemptionRepository.save(CouponRedemption.builder()
                .couponId(coupon.getId())
                .userId(userId)
                .orderId(orderId)
                .discountAmount(discount)
                .redeemedAt(LocalDateTime.now(clock))
                .build());

        return RedeemCouponResponse.builder()
                .code(coupon.getCode())
                .orderAmount(orderAmount.setScale(2, RoundingMode.HALF_UP))
                .discountAmount(discount)
                .payable(orderAmount.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP))
                .build();
    }

    /** Throws if the coupon is inactive, expired, or its (total / per-user) limit is hit. */
    private void checkUsable(Coupon coupon, Long userId) {
        if (!coupon.isActive()) {
            throw couponInvalid("Coupon is not active");
        }
        if (coupon.getExpiresAt() != null && coupon.getExpiresAt().isBefore(LocalDateTime.now(clock))) {
            throw couponInvalid("Coupon has expired");
        }
        if (coupon.getMaxRedemptions() != null
                && redemptionRepository.countByCouponId(coupon.getId()) >= coupon.getMaxRedemptions()) {
            throw couponInvalid("Coupon redemption limit reached");
        }
        if (coupon.getPerUserLimit() != null
                && redemptionRepository.countByCouponIdAndUserId(coupon.getId(), userId) >= coupon.getPerUserLimit()) {
            throw couponInvalid("You have already used this coupon");
        }
    }

    private MembershipException couponNotFound(String code) {
        return new MembershipException("Unknown coupon '" + code + "'", "COUPON_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    private BigDecimal discountFor(Coupon coupon, BigDecimal amount) {
        BigDecimal base = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal discount = coupon.getDiscountType() == Coupon.DiscountType.PERCENT
                ? base.multiply(coupon.getDiscountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : coupon.getDiscountValue();
        return discount.min(base).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP); // never exceed the amount
    }

    private MembershipException couponInvalid(String message) {
        return new MembershipException(message, "COUPON_INVALID", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private CouponDTO toDto(Coupon c) {
        return CouponDTO.builder()
                .id(c.getId()).code(c.getCode()).description(c.getDescription())
                .discountType(c.getDiscountType()).discountValue(c.getDiscountValue())
                .maxRedemptions(c.getMaxRedemptions()).perUserLimit(c.getPerUserLimit())
                .active(c.isActive()).expiresAt(c.getExpiresAt())
                .build();
    }
}
