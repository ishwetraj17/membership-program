package com.firstclub.billing.tax.entity;

/** Classification of a customer for GST purposes. */
public enum CustomerEntityType {
    /** Natural person / retail consumer. */
    INDIVIDUAL,
    /** Registered business entity (may have a GSTIN). */
    BUSINESS
}
