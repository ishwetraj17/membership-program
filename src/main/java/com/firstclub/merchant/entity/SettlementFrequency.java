package com.firstclub.merchant.entity;

/**
 * How often the settlement batch job sweeps captured payments to the merchant's bank account.
 */
public enum SettlementFrequency {
    /** Settle every calendar day (T+0 cutoff). */
    DAILY,
    /** Settle the following business day (standard T+1 NEFT cycle). */
    T_PLUS_1,
    /** Weekly settlement batch (every Monday for the prior week). */
    WEEKLY
}
