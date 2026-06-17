package com.firstclub.membership.service;

import com.firstclub.membership.dto.CheckoutQuoteRequest;
import com.firstclub.membership.dto.CheckoutQuoteResponse;
import com.firstclub.membership.dto.OrderDTO;

/**
 * Applies a user's membership benefits to a cart — the bridge between the membership program
 * and the checkout journey.
 */
public interface CheckoutService {

    /** Price a cart with benefits + optional coupon previewed (no side effects). */
    CheckoutQuoteResponse quote(CheckoutQuoteRequest request);

    /** Place an order, atomically redeeming the coupon (if any) against it. */
    OrderDTO confirm(CheckoutQuoteRequest request);
}
