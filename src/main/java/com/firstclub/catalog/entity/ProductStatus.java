package com.firstclub.catalog.entity;

/**
 * Lifecycle status for a {@link Product}.
 *
 * <ul>
 *   <li>{@link #ACTIVE}   – product is available for new subscriptions/orders.</li>
 *   <li>{@link #ARCHIVED} – product is retired; no new subscriptions may reference it.</li>
 * </ul>
 */
public enum ProductStatus {
    ACTIVE,
    ARCHIVED
}
