package com.firstclub.recon.repository;

/**
 * Projection for duplicate settlement batch detection.
 *
 * <p>Returned by {@link SettlementBatchRepository#findDuplicateMerchantBatchesForDate}
 * for any {@code (merchant_id, batch_date)} combination that has more than one
 * {@link com.firstclub.recon.entity.SettlementBatch} row.
 */
public interface DuplicateBatchProjection {
    Long getMerchantId();
    long getBatchCount();
}
