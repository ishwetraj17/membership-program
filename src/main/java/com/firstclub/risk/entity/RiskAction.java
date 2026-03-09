package com.firstclub.risk.entity;

public enum RiskAction {
    /** Payment proceeds normally. */
    ALLOW,
    /** Route to additional authentication (e.g. 3DS); sets intent to REQUIRES_ACTION. */
    CHALLENGE,
    /** Hard block — payment intent transitions to FAILED. */
    BLOCK,
    /** Pause payment and open a manual review case; sets intent to REQUIRES_ACTION. */
    REVIEW
}
