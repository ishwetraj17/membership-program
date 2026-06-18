package com.firstclub.membership.service;

import com.firstclub.membership.service.benefit.BenefitEvaluation;
import com.firstclub.membership.service.benefit.CartContext;

/**
 * Evaluates a membership tier's configured benefit rules against a cart, producing the discount and
 * fee waivers to apply. Pure with respect to the cart — it only reads rule configuration — so it can
 * be used both to price a quote and to describe a member's entitlements.
 */
public interface BenefitEngine {

    BenefitEvaluation evaluate(Long tierId, CartContext cart);
}
