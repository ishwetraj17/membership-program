package com.firstclub.reporting.ops.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.firstclub.platform.redis.RedisJsonCodec;
import com.firstclub.platform.redis.RedisKeyFactory;
import com.firstclub.reporting.ops.dto.InvoiceSummaryProjectionDTO;
import com.firstclub.reporting.ops.dto.PaymentSummaryProjectionDTO;
import com.firstclub.reporting.ops.dto.SubscriptionStatusProjectionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Optional hot-cache layer on top of the ops projection tables.
 *
 * <h3>Key patterns</h3>
 * <pre>
 * {env}:firstclub:proj:sub-status:{merchantId}:{subscriptionId}
 * {env}:firstclub:proj:invoice-summary:{merchantId}:{invoiceId}
 * {env}:firstclub:proj:payment-summary:{merchantId}:{paymentIntentId}
 * </pre>
 *
 * <h3>TTL</h3>
 * {@value #TTL_SECONDS} seconds (2 min).  The projection tables are always the
 * authoritative fallback — callers must DB-fall-back on a cache miss.
 *
 * <h3>Degradation</h3>
 * All methods are safe when Redis is unavailable: reads return
 * {@link Optional#empty()} and writes / evictions are silently ignored.
 */
@Slf4j
@Component
public class OpsProjectionCacheService {

    public static final int TTL_SECONDS = 120;

    private static final TypeReference<SubscriptionStatusProjectionDTO> SUB_TYPE     = new TypeReference<>() {};
    private static final TypeReference<InvoiceSummaryProjectionDTO>     INVOICE_TYPE = new TypeReference<>() {};
    private static final TypeReference<PaymentSummaryProjectionDTO>     PAYMENT_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory                     keyFactory;
    private final RedisJsonCodec                      codec;

    public OpsProjectionCacheService(ObjectProvider<StringRedisTemplate> templateProvider,
                                     RedisKeyFactory keyFactory,
                                     RedisJsonCodec codec) {
        this.templateProvider = templateProvider;
        this.keyFactory       = keyFactory;
        this.codec            = codec;
    }

    // ── Subscription status ───────────────────────────────────────────────

    public Optional<SubscriptionStatusProjectionDTO> getSubStatus(Long merchantId, Long subscriptionId) {
        StringRedisTemplate t = template();
        if (t == null) return Optional.empty();
        try {
            String key  = keyFactory.projSubStatusKey(str(merchantId), str(subscriptionId));
            String json = t.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.ofNullable(codec.fromJson(json, SUB_TYPE));
        } catch (Exception ex) {
            log.warn("OpsCache: sub-status read failed merchant={} sub={}", merchantId, subscriptionId, ex);
            return Optional.empty();
        }
    }

    public void putSubStatus(Long merchantId, Long subscriptionId, SubscriptionStatusProjectionDTO dto) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            String key  = keyFactory.projSubStatusKey(str(merchantId), str(subscriptionId));
            t.opsForValue().set(key, codec.toJson(dto), Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception ex) {
            log.warn("OpsCache: sub-status write failed merchant={} sub={}", merchantId, subscriptionId, ex);
        }
    }

    public void evictSubStatus(Long merchantId, Long subscriptionId) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            t.delete(keyFactory.projSubStatusKey(str(merchantId), str(subscriptionId)));
        } catch (Exception ex) {
            log.warn("OpsCache: sub-status evict failed merchant={} sub={}", merchantId, subscriptionId, ex);
        }
    }

    // ── Invoice summary ───────────────────────────────────────────────────

    public Optional<InvoiceSummaryProjectionDTO> getInvoiceSummary(Long merchantId, Long invoiceId) {
        StringRedisTemplate t = template();
        if (t == null) return Optional.empty();
        try {
            String key  = keyFactory.projInvoiceSummaryKey(str(merchantId), str(invoiceId));
            String json = t.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.ofNullable(codec.fromJson(json, INVOICE_TYPE));
        } catch (Exception ex) {
            log.warn("OpsCache: invoice-summary read failed merchant={} invoice={}", merchantId, invoiceId, ex);
            return Optional.empty();
        }
    }

    public void putInvoiceSummary(Long merchantId, Long invoiceId, InvoiceSummaryProjectionDTO dto) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            String key = keyFactory.projInvoiceSummaryKey(str(merchantId), str(invoiceId));
            t.opsForValue().set(key, codec.toJson(dto), Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception ex) {
            log.warn("OpsCache: invoice-summary write failed merchant={} invoice={}", merchantId, invoiceId, ex);
        }
    }

    public void evictInvoiceSummary(Long merchantId, Long invoiceId) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            t.delete(keyFactory.projInvoiceSummaryKey(str(merchantId), str(invoiceId)));
        } catch (Exception ex) {
            log.warn("OpsCache: invoice-summary evict failed merchant={} invoice={}", merchantId, invoiceId, ex);
        }
    }

    // ── Payment summary ───────────────────────────────────────────────────

    public Optional<PaymentSummaryProjectionDTO> getPaymentSummary(Long merchantId, Long paymentIntentId) {
        StringRedisTemplate t = template();
        if (t == null) return Optional.empty();
        try {
            String key  = keyFactory.projPaymentSummaryKey(str(merchantId), str(paymentIntentId));
            String json = t.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.ofNullable(codec.fromJson(json, PAYMENT_TYPE));
        } catch (Exception ex) {
            log.warn("OpsCache: payment-summary read failed merchant={} intent={}", merchantId, paymentIntentId, ex);
            return Optional.empty();
        }
    }

    public void putPaymentSummary(Long merchantId, Long paymentIntentId, PaymentSummaryProjectionDTO dto) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            String key = keyFactory.projPaymentSummaryKey(str(merchantId), str(paymentIntentId));
            t.opsForValue().set(key, codec.toJson(dto), Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception ex) {
            log.warn("OpsCache: payment-summary write failed merchant={} intent={}", merchantId, paymentIntentId, ex);
        }
    }

    public void evictPaymentSummary(Long merchantId, Long paymentIntentId) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            t.delete(keyFactory.projPaymentSummaryKey(str(merchantId), str(paymentIntentId)));
        } catch (Exception ex) {
            log.warn("OpsCache: payment-summary evict failed merchant={} intent={}", merchantId, paymentIntentId, ex);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private StringRedisTemplate template() {
        return templateProvider.getIfAvailable();
    }

    private static String str(Long value) {
        return value == null ? "null" : value.toString();
    }
}
