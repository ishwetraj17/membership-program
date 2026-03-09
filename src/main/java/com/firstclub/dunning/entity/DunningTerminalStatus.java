package com.firstclub.dunning.entity;

/**
 * Terminal subscription status applied by DunningServiceV2 when all
 * dunning retries are exhausted and the grace period has expired.
 */
public enum DunningTerminalStatus {

    /** Subscription is suspended; re-activation requires manual intervention. */
    SUSPENDED,

    /** Subscription is cancelled immediately; no re-activation possible. */
    CANCELLED
}
