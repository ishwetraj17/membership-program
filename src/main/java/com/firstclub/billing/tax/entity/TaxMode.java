package com.firstclub.billing.tax.entity;

/** GST regime the merchant operates under. */
public enum TaxMode {
    /** Business-to-business: full tax invoice issued. */
    B2B,
    /** Business-to-consumer: simplified tax invoice issued. */
    B2C
}
