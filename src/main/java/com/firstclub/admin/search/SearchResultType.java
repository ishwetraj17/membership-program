package com.firstclub.admin.search;

/**
 * Discriminator for a {@link SearchResultDTO} — describes which entity table
 * the result was retrieved from.
 */
public enum SearchResultType {
    INVOICE,
    PAYMENT_INTENT,
    PAYMENT,
    REFUND,
    CUSTOMER,
    SUBSCRIPTION,
    DOMAIN_EVENT
}
