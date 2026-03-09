package com.firstclub.payments.refund.service.impl;

import com.firstclub.events.service.DomainEventLog;
import com.firstclub.membership.exception.MembershipException;
import com.firstclub.outbox.config.DomainEventTypes;
import com.firstclub.outbox.service.OutboxService;
import com.firstclub.payments.capacity.PaymentCapacityInvariantService;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.dto.RefundCreateRequestDTO;
import com.firstclub.payments.refund.dto.RefundV2ResponseDTO;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.guard.RefundMutationGuard;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.refund.service.RefundAccountingService;
import com.firstclub.payments.refund.service.RefundServiceV2;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.platform.redis.RedisKeyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundServiceV2Impl implements RefundServiceV2 {

    private static final Set<PaymentStatus> REFUNDABLE_STATUSES =
            Set.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);

    /** TTL for the optional Redis processing lock (supplement to DB SELECT FOR UPDATE). */
    private static final Duration REDIS_LOCK_TTL = Duration.ofSeconds(10);

    private final PaymentRepository              paymentRepository;
    private final RefundV2Repository             refundV2Repository;
    private final RefundAccountingService        refundAccountingService;
    private final OutboxService                  outboxService;
    private final DomainEventLog                 domainEventLog;
    private final RedisKeyFactory                redisKeyFactory;
    private final RefundMutationGuard            refundMutationGuard;
    private final PaymentCapacityInvariantService invariantService;

    /**
     * Optional — injected only when Redis is available in the current environment.
     * When absent all lock checks silently pass through to the DB-level pessimistic lock.
     */
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    @Override
    @Transactional
    public RefundV2ResponseDTO createRefund(Long merchantId, Long paymentId, RefundCreateRequestDTO request) {

        // 0. Compute or accept idempotency fingerprint BEFORE acquiring any lock
        String fingerprint = (request.getRequestFingerprint() != null && !request.getRequestFingerprint().isBlank())
                ? request.getRequestFingerprint()
                : computeFingerprint(merchantId, paymentId, request.getAmount(), request.getReasonCode());

        // 0a. Fast-path idempotency check: if fingerprint already exists, return existing refund
        Optional<RefundV2> existing = refundV2Repository.findByRequestFingerprint(fingerprint);
        if (existing.isPresent()) {
            log.info("Refund V2 fingerprint replay — returning existing refundId={} for paymentId={}",
                    existing.get().getId(), paymentId);
            Payment payment = paymentRepository.findById(existing.get().getPaymentId())
                    .orElseThrow(() -> new MembershipException(
                            "Parent payment not found: " + existing.get().getPaymentId(),
                            "PAYMENT_NOT_FOUND", HttpStatus.NOT_FOUND));
            return toDto(existing.get(), payment);
        }

        // 0b. Optional Redis pre-lock (supplements SELECT FOR UPDATE to reduce DB contention)
        String lockKey = redisKeyFactory.refundLockKey(String.valueOf(paymentId));
        boolean redisLockAcquired = tryAcquireRedisLock(lockKey);

        try {
            // 1. Acquire pessimistic write lock on Payment and validate capacity.
            //    RefundMutationGuard delegates the SELECT FOR UPDATE + capacity check to
            //    RefundCapacityService, ensuring both steps run on the latest committed row.
            Payment payment = refundMutationGuard.acquireAndCheck(paymentId, request.getAmount());

            // 2. Tenant isolation — every payment created via the V2 flow has merchant_id set
            validateMerchantOwnership(merchantId, payment);

            // 3. Only CAPTURED or PARTIALLY_REFUNDED payments can be refunded
            if (!REFUNDABLE_STATUSES.contains(payment.getStatus())) {
                throw new MembershipException(
                        "Payment " + paymentId + " has status " + payment.getStatus() + " and cannot be refunded",
                        "PAYMENT_NOT_REFUNDABLE",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // 5. Persist the refund as PENDING, storing the fingerprint for future idempotency checks
            RefundV2 refund = refundV2Repository.save(RefundV2.builder()
                    .merchantId(merchantId)
                    .paymentId(paymentId)
                    .invoiceId(request.getInvoiceId())
                    .amount(request.getAmount())
                    .reasonCode(request.getReasonCode())
                    .status(RefundV2Status.PENDING)
                    .refundReference(request.getRefundReference())
                    .requestFingerprint(fingerprint)
                    .build());

            // 6. Post reversal double-entry to the ledger (DR SUBSCRIPTION_LIABILITY / CR PG_CLEARING)
            refundAccountingService.postRefundReversal(refund, payment);

            // 7. Update cumulative amounts on the payment
            BigDecimal newRefundedAmount = payment.getRefundedAmount().add(request.getAmount());
            payment.setRefundedAmount(newRefundedAmount);
            payment.setNetAmount(
                    payment.getCapturedAmount()
                            .subtract(newRefundedAmount)
                            .subtract(payment.getDisputedAmount()));

            // 8. Transition payment status
            if (newRefundedAmount.compareTo(payment.getCapturedAmount()) >= 0) {
                payment.setStatus(PaymentStatus.REFUNDED);
            } else {
                payment.setStatus(PaymentStatus.PARTIALLY_REFUNDED);
            }
            // Sync minor-unit fields so the DB capacity constraint stays satisfiable
            invariantService.syncMinorUnitFields(payment);
            paymentRepository.save(payment);

            // 9. Complete the refund atomically in the same transaction
            refund.setStatus(RefundV2Status.COMPLETED);
            refund.setCompletedAt(LocalDateTime.now());
            refund = refundV2Repository.save(refund);

            log.info("Refund V2 {} COMPLETED — paymentId={}, merchantId={}, amount={} {}",
                    refund.getId(), paymentId, merchantId, request.getAmount(), payment.getCurrency());

            // 10. Append-only audit trail + outbox event
            domainEventLog.record("REFUND_V2_ISSUED", Map.of(
                    "refundId",  refund.getId(),
                    "paymentId", paymentId,
                    "merchantId", merchantId,
                    "amount",    refund.getAmount().toPlainString(),
                    "currency",  payment.getCurrency()));

            outboxService.publish(DomainEventTypes.REFUND_ISSUED, Map.of(
                    "refundId",  refund.getId(),
                    "paymentId", paymentId,
                    "merchantId", merchantId,
                    "amount",    refund.getAmount().toPlainString(),
                    "currency",  payment.getCurrency()));

            return toDto(refund, payment);

        } finally {
            if (redisLockAcquired) {
                releaseRedisLock(lockKey);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public RefundV2ResponseDTO getRefund(Long merchantId, Long refundId) {
        RefundV2 refund = refundV2Repository.findByMerchantIdAndId(merchantId, refundId)
                .orElseThrow(() -> new MembershipException(
                        "Refund not found: " + refundId,
                        "REFUND_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        Payment payment = paymentRepository.findById(refund.getPaymentId())
                .orElseThrow(() -> new MembershipException(
                        "Parent payment not found: " + refund.getPaymentId(),
                        "PAYMENT_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        return toDto(refund, payment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundV2ResponseDTO> listRefundsByPayment(Long merchantId, Long paymentId) {
        // Validate the payment belongs to this merchant
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new MembershipException(
                        "Payment not found: " + paymentId,
                        "PAYMENT_NOT_FOUND",
                        HttpStatus.NOT_FOUND));

        validateMerchantOwnership(merchantId, payment);

        return refundV2Repository.findByPaymentIdAndMerchantId(paymentId, merchantId)
                .stream()
                .map(r -> toDto(r, payment))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal computeRefundableAmount(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new MembershipException(
                        "Payment not found: " + paymentId,
                        "PAYMENT_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
        return computeRefundableAmount(payment);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BigDecimal computeRefundableAmount(Payment payment) {
        return payment.getCapturedAmount()
                .subtract(payment.getRefundedAmount())
                .subtract(payment.getDisputedAmount());
    }

    private void validateMerchantOwnership(Long merchantId, Payment payment) {
        if (payment.getMerchantId() == null) {
            throw new MembershipException(
                    "Payment " + payment.getId() + " has no merchant association and cannot be refunded via the merchant API",
                    "PAYMENT_MERCHANT_UNRESOLVED",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!merchantId.equals(payment.getMerchantId())) {
            throw new MembershipException(
                    "Payment " + payment.getId() + " does not belong to merchant " + merchantId,
                    "PAYMENT_MERCHANT_MISMATCH",
                    HttpStatus.FORBIDDEN);
        }
    }

    private RefundV2ResponseDTO toDto(RefundV2 refund, Payment payment) {
        BigDecimal refundableAfter = payment.getCapturedAmount()
                .subtract(payment.getRefundedAmount())
                .subtract(payment.getDisputedAmount());

        return RefundV2ResponseDTO.builder()
                .id(refund.getId())
                .merchantId(refund.getMerchantId())
                .paymentId(refund.getPaymentId())
                .invoiceId(refund.getInvoiceId())
                .amount(refund.getAmount())
                .reasonCode(refund.getReasonCode())
                .status(refund.getStatus())
                .refundReference(refund.getRefundReference())
                .requestFingerprint(refund.getRequestFingerprint())
                .createdAt(refund.getCreatedAt())
                .completedAt(refund.getCompletedAt())
                .refundableAmountAfter(refundableAfter)
                .paymentStatusAfter(payment.getStatus())
                .build();
    }

    /**
     * Generates a deterministic SHA-256 fingerprint for the refund request.
     * Format: {@code SHA-256(merchantId:paymentId:amount.toPlainString():reasonCode)}
     */
    private String computeFingerprint(Long merchantId, Long paymentId, BigDecimal amount, String reasonCode) {
        String input = merchantId + ":" + paymentId + ":" + amount.toPlainString() + ":" + reasonCode;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — this branch is unreachable in practice
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Tries to acquire a Redis SET NX lock for the given key.
     * Returns {@code true} if the lock was acquired OR if Redis is unavailable (fall-through).
     * The DB-level pessimistic write lock remains the authoritative concurrency guard.
     */
    private boolean tryAcquireRedisLock(String key) {
        try {
            StringRedisTemplate tmpl = redisProvider.getIfAvailable();
            if (tmpl == null) return true;
            Boolean set = tmpl.opsForValue().setIfAbsent(key, "1", REDIS_LOCK_TTL);
            if (!Boolean.TRUE.equals(set)) {
                log.warn("Redis refund lock contention on key={} — falling through to DB lock", key);
            }
            return Boolean.TRUE.equals(set);
        } catch (Exception e) {
            log.warn("Redis unavailable for refund lock key={}: {} — falling through to DB lock",
                    key, e.getMessage());
            return true;
        }
    }

    private void releaseRedisLock(String key) {
        try {
            StringRedisTemplate tmpl = redisProvider.getIfAvailable();
            if (tmpl != null) {
                tmpl.delete(key);
            }
        } catch (Exception e) {
            log.warn("Failed to release Redis refund lock key={}: {}", key, e.getMessage());
        }
    }
}
