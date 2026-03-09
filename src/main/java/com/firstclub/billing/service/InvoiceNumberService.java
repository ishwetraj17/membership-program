package com.firstclub.billing.service;

/**
 * Generates merchant-scoped, sequentially numbered invoice identifiers.
 *
 * <p>Format: {@code {prefix}-{NNNNNN}} — e.g. {@code FCM-000001}.
 * The counter is incremented atomically under a pessimistic write lock so
 * concurrent requests for the same merchant never produce duplicates.
 */
public interface InvoiceNumberService {

    /**
     * Returns the next invoice number for the given merchant, incrementing
     * the sequence.  If no sequence exists yet a new one is created with
     * prefix {@code "INV"}.
     *
     * <p><strong>Must be called inside an active transaction.</strong>
     */
    String generateNextInvoiceNumber(Long merchantId);

    /**
     * Creates or replaces the sequence row for a merchant.  Useful for
     * administrative setup (e.g. setting a custom prefix).
     */
    void initSequence(Long merchantId, String prefix);
}
