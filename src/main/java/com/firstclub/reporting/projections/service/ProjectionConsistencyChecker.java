package com.firstclub.reporting.projections.service;

import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.reporting.projections.dto.ConsistencyReport;
import com.firstclub.reporting.projections.entity.CustomerPaymentSummaryProjection;
import com.firstclub.reporting.projections.entity.MerchantRevenueProjection;
import com.firstclub.reporting.projections.repository.CustomerPaymentSummaryProjectionRepository;
import com.firstclub.reporting.projections.repository.MerchantRevenueProjectionRepository;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Samples a projection row and compares it against the live authoritative source.
 *
 * <p>This is a diagnostic / correctness-verification service. It is meant to
 * be invoked on-demand (e.g. from the admin projection API) rather than in
 * the hot payment path.
 *
 * <p>Results are returned as {@link ConsistencyReport} instances describing
 * whether the sampled field matches the source and, if not, by how much.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProjectionConsistencyChecker {

    private final CustomerPaymentSummaryProjectionRepository customerPaymentRepo;
    private final MerchantRevenueProjectionRepository        merchantRevenueRepo;
    private final PaymentIntentV2Repository                  paymentIntentRepo;
    private final SubscriptionV2Repository                   subscriptionRepo;

    /**
     * Compare the {@code customer_payment_summary_projection} for a given
     * (merchantId, customerId) pair against a live count of
     * {@link com.firstclub.payments.entity.PaymentIntentV2} rows.
     *
     * <p>Checks the {@code successfulPayments} counter only (a quick sanity
     * check that is cheap to compute from source).
     *
     * @return report indicating whether the projection is consistent
     */
    public ConsistencyReport checkCustomerPaymentSummary(Long merchantId, Long customerId) {
        CustomerPaymentSummaryProjection proj = customerPaymentRepo
                .findByMerchantIdAndCustomerId(merchantId, customerId)
                .orElse(null);

        long projectionValue = proj == null ? 0L : proj.getSuccessfulPayments();

        List<PaymentIntentV2> succeeded = paymentIntentRepo
                .findByMerchantIdAndCustomerIdAndStatus(merchantId, customerId, PaymentIntentStatusV2.SUCCEEDED);
        long sourceValue = succeeded.size();
        long delta = sourceValue - projectionValue;

        log.debug("customer_payment_summary consistency: merchant={} customer={} " +
                  "projection={} source={} delta={}", merchantId, customerId, projectionValue, sourceValue, delta);

        return ConsistencyReport.builder()
                .projectionName("customer_payment_summary")
                .key("merchant=" + merchantId + ",customer=" + customerId)
                .consistent(delta == 0L)
                .projectionValue(projectionValue)
                .sourceValue(sourceValue)
                .delta(delta)
                .checkedField("successfulPayments")
                .build();
    }

    /**
     * Compare the {@code merchant_revenue_projection} for a given merchant
     * against a live count of active subscriptions from the
     * {@link com.firstclub.subscription.entity.SubscriptionV2} table.
     *
     * <p>Checks the {@code activeSubscriptions} counter only.
     *
     * @return report indicating whether the projection is consistent
     */
    public ConsistencyReport checkMerchantRevenue(Long merchantId) {
        MerchantRevenueProjection proj = merchantRevenueRepo.findById(merchantId).orElse(null);

        long projectionValue = proj == null ? 0L : proj.getActiveSubscriptions();

        long sourceValue = subscriptionRepo
                .findByMerchantIdAndStatus(merchantId, SubscriptionStatusV2.ACTIVE,
                        PageRequest.of(0, 1))
                .getTotalElements();
        long delta = sourceValue - projectionValue;

        log.debug("merchant_revenue consistency: merchant={} projection={} source={} delta={}",
                  merchantId, projectionValue, sourceValue, delta);

        return ConsistencyReport.builder()
                .projectionName("merchant_revenue")
                .key("merchant=" + merchantId)
                .consistent(delta == 0L)
                .projectionValue(projectionValue)
                .sourceValue(sourceValue)
                .delta(delta)
                .checkedField("activeSubscriptions")
                .build();
    }
}
