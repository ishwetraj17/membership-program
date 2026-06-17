package com.firstclub.membership.service;

import com.firstclub.membership.dto.CouponDTO;
import com.firstclub.membership.dto.CreateCouponRequest;
import com.firstclub.membership.dto.RedeemCouponResponse;

import java.math.BigDecimal;
import java.util.List;

public interface CouponService {

    CouponDTO createCoupon(CreateCouponRequest request);

    List<CouponDTO> listCoupons();

    CouponDTO deactivateCoupon(String code);

    /** Validate a coupon for a user and compute its discount against an amount — no redemption. */
    BigDecimal previewDiscount(String code, Long userId, BigDecimal amount);

    /** Validate, record a redemption (enforcing limits), and return the discount applied. */
    RedeemCouponResponse redeem(String code, Long userId, BigDecimal orderAmount);

    /** As {@link #redeem}, tying the redemption to a placed order. */
    RedeemCouponResponse redeem(String code, Long userId, Long orderId, BigDecimal orderAmount);
}
