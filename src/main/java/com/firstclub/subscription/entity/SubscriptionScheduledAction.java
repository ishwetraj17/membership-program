package com.firstclub.subscription.entity;

/**
 * Actions that can be queued in {@link SubscriptionSchedule} and applied
 * to a {@link SubscriptionV2} at a future point in time.
 */
public enum SubscriptionScheduledAction {

    /** Move the subscription to a different {@link com.firstclub.catalog.entity.Price}
     *  on the same product. Payload must include {@code newPriceId}. */
    CHANGE_PRICE,

    /** Pause the subscription at the scheduled time. Payload may include
     *  {@code pauseEndsAt} for an auto-resume time. */
    PAUSE,

    /** Resume a paused subscription at the scheduled time. */
    RESUME,

    /** Cancel the subscription at the scheduled time (respects {@code cancelAtPeriodEnd}
     *  semantics when scheduled at period end). */
    CANCEL,

    /** Atomically swap the price to a different SKU/plan. Similar to
     *  {@code CHANGE_PRICE} but may also change the product. Payload must
     *  include {@code newPriceId} and optionally {@code newProductId}. */
    SWAP_PRICE
}
