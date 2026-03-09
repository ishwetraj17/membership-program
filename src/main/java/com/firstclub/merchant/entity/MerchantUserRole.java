package com.firstclub.merchant.entity;

/**
 * Role of a platform user within a specific merchant context.
 *
 * <p>One user may hold different roles across different merchants.
 * Role hierarchy (descending privilege):
 * OWNER > ADMIN > OPERATIONS > SUPPORT > READ_ONLY
 */
public enum MerchantUserRole {
    /** Full ownership — can delete merchant, manage all users including other OWNERs. */
    OWNER,
    /** Administrative access — can manage settings and users below ADMIN level. */
    ADMIN,
    /** Day-to-day ops — can process refunds, manage subscriptions, run reports. */
    OPERATIONS,
    /** Customer support — read access plus ability to issue credit notes. */
    SUPPORT,
    /** Strictly read-only access to dashboard and reports. */
    READ_ONLY
}
