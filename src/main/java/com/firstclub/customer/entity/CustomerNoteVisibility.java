package com.firstclub.customer.entity;

/**
 * Controls who can see a {@link CustomerNote}.
 *
 * <ul>
 *   <li>{@code INTERNAL_ONLY}     – visible only to platform operators and admins
 *                                   (not shown to merchant-facing UIs).</li>
 *   <li>{@code MERCHANT_VISIBLE}  – visible to the merchant's own staff in addition
 *                                   to platform operators.</li>
 * </ul>
 */
public enum CustomerNoteVisibility {
    INTERNAL_ONLY,
    MERCHANT_VISIBLE
}
