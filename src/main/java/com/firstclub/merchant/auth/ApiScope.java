package com.firstclub.merchant.auth;

import java.util.List;

/**
 * Canonical scope constants for merchant API key access control.
 *
 * Scopes follow the format {@code resource:action}.
 * Keys are created with a subset of these scopes, and future request filters
 * can enforce scope checks per endpoint.
 */
public final class ApiScope {

    private ApiScope() {}

    public static final String CUSTOMERS_READ      = "customers:read";
    public static final String CUSTOMERS_WRITE     = "customers:write";
    public static final String SUBSCRIPTIONS_READ  = "subscriptions:read";
    public static final String SUBSCRIPTIONS_WRITE = "subscriptions:write";
    public static final String INVOICES_READ       = "invoices:read";
    public static final String REFUNDS_WRITE       = "refunds:write";
    public static final String PAYMENTS_READ       = "payments:read";
    public static final String PAYMENTS_WRITE      = "payments:write";

    /** All available scopes — use this to create a full-access key. */
    public static final List<String> ALL = List.of(
            CUSTOMERS_READ, CUSTOMERS_WRITE,
            SUBSCRIPTIONS_READ, SUBSCRIPTIONS_WRITE,
            INVOICES_READ, REFUNDS_WRITE,
            PAYMENTS_READ, PAYMENTS_WRITE
    );
}
