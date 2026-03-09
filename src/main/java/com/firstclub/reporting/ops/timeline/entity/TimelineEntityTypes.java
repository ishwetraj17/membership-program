package com.firstclub.reporting.ops.timeline.entity;

/**
 * String constants for the {@code entity_type} column in
 * {@link TimelineEvent}.  Using a constants class (rather than enum)
 * keeps the column a plain {@code VARCHAR} in the DB and avoids Hibernate
 * enum-mapping boilerplate.
 */
public final class TimelineEntityTypes {

    private TimelineEntityTypes() {}

    public static final String CUSTOMER       = "CUSTOMER";
    public static final String SUBSCRIPTION   = "SUBSCRIPTION";
    public static final String INVOICE        = "INVOICE";
    public static final String PAYMENT_INTENT = "PAYMENT_INTENT";
    public static final String REFUND         = "REFUND";
    public static final String DISPUTE        = "DISPUTE";
    public static final String RECON_MISMATCH = "RECON_MISMATCH";
    public static final String SUPPORT_CASE   = "SUPPORT_CASE";
}
