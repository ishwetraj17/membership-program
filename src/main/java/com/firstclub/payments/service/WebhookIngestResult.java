package com.firstclub.payments.service;

/**
 * Result of ingesting a single inbound webhook event.
 */
public enum WebhookIngestResult {

    /** Event was parsed, verified, and processed successfully. */
    PROCESSED,

    /** Event was already processed (idempotency guard triggered). */
    DUPLICATE,

    /** Signature verification failed — event was stored but not acted on. */
    INVALID_SIGNATURE,

    /** An unexpected error occurred during ingestion. */
    ERROR
}
