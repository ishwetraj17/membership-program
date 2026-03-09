package com.firstclub.subscription.service.impl;

import com.firstclub.catalog.entity.Price;
import com.firstclub.catalog.entity.PriceVersion;
import com.firstclub.catalog.entity.Product;
import com.firstclub.catalog.exception.CatalogException;
import com.firstclub.catalog.repository.PriceRepository;
import com.firstclub.catalog.repository.PriceVersionRepository;
import com.firstclub.catalog.repository.ProductRepository;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.exception.CustomerException;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.merchant.exception.MerchantException;
import com.firstclub.merchant.repository.MerchantAccountRepository;
import com.firstclub.subscription.dto.SubscriptionCreateRequestDTO;
import com.firstclub.subscription.dto.SubscriptionResponseDTO;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.exception.SubscriptionException;
import com.firstclub.subscription.exception.SubscriptionStateMachine;
import com.firstclub.subscription.mapper.SubscriptionV2Mapper;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import com.firstclub.subscription.service.SubscriptionV2Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Implementation of {@link SubscriptionV2Service}.
 *
 * <p>Business rules enforced:
 * <ul>
 *   <li>customer, product, price, priceVersion must all belong to the same merchant.</li>
 *   <li>Subscription starts as TRIALING if price.trialDays > 0; otherwise INCOMPLETE.</li>
 *   <li>At most one non-terminal subscription per (merchant, customer, product) by default.</li>
 *   <li>Cancel, pause, resume transitions are validated against the state machine.</li>
 *   <li>cancelAtPeriodEnd sets a flag but keeps the subscription ACTIVE until period end.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionV2ServiceImpl implements SubscriptionV2Service {

    /** States that block a new subscription for the same customer+product. */
    private static final Set<SubscriptionStatusV2> BLOCKING_STATUSES = EnumSet.of(
            SubscriptionStatusV2.INCOMPLETE,
            SubscriptionStatusV2.TRIALING,
            SubscriptionStatusV2.ACTIVE,
            SubscriptionStatusV2.PAST_DUE,
            SubscriptionStatusV2.PAUSED,
            SubscriptionStatusV2.SUSPENDED
    );

    private final SubscriptionV2Repository subscriptionRepository;
    private final MerchantAccountRepository merchantAccountRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final PriceRepository priceRepository;
    private final PriceVersionRepository priceVersionRepository;
    private final SubscriptionV2Mapper mapper;

    // ── Create ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SubscriptionResponseDTO createSubscription(Long merchantId, SubscriptionCreateRequestDTO request) {
        log.info("Creating subscription for merchantId={}, customerId={}, productId={}, priceId={}",
                merchantId, request.getCustomerId(), request.getProductId(), request.getPriceId());

        MerchantAccount merchant = loadMerchantOrThrow(merchantId);
        Customer customer = loadCustomerOrThrow(merchantId, request.getCustomerId());
        Product product = loadProductOrThrow(merchantId, request.getProductId());
        Price price = loadPriceOrThrow(merchantId, request.getPriceId());

        // Validate price belongs to the requested product
        if (!price.getProduct().getId().equals(product.getId())) {
            throw CatalogException.priceNotFound(merchantId, request.getPriceId());
        }

        // Resolve price version
        PriceVersion priceVersion = resolvePriceVersion(request, price);

        // Duplicate active subscription guard
        if (subscriptionRepository.existsByMerchantIdAndCustomerIdAndProductIdAndStatusIn(
                merchantId, customer.getId(), product.getId(), BLOCKING_STATUSES)) {
            throw SubscriptionException.duplicateActiveSubscription(customer.getId(), product.getId());
        }

        // Determine initial status + trial
        LocalDateTime now = LocalDateTime.now();
        SubscriptionStatusV2 initialStatus;
        LocalDateTime trialEndsAt = null;

        if (price.getTrialDays() > 0) {
            initialStatus = SubscriptionStatusV2.TRIALING;
            trialEndsAt = now.plusDays(price.getTrialDays());
        } else {
            initialStatus = SubscriptionStatusV2.INCOMPLETE;
        }

        SubscriptionV2 sub = SubscriptionV2.builder()
                .merchant(merchant)
                .customer(customer)
                .product(product)
                .price(price)
                .priceVersion(priceVersion)
                .status(initialStatus)
                .billingAnchorAt(now)
                .currentPeriodStart(now)
                .trialEndsAt(trialEndsAt)
                .metadataJson(request.getMetadataJson())
                .build();

        SubscriptionV2 saved = subscriptionRepository.save(sub);
        log.info("Subscription created: id={}, status={}", saved.getId(), saved.getStatus());
        return mapper.toResponseDTO(saved);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SubscriptionResponseDTO getSubscriptionById(Long merchantId, Long subscriptionId) {
        return mapper.toResponseDTO(loadOrThrow(merchantId, subscriptionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SubscriptionResponseDTO> listSubscriptions(Long merchantId, SubscriptionStatusV2 status, Pageable pageable) {
        Page<SubscriptionV2> page = (status != null)
                ? subscriptionRepository.findByMerchantIdAndStatus(merchantId, status, pageable)
                : subscriptionRepository.findByMerchantId(merchantId, pageable);
        return page.map(mapper::toResponseDTO);
    }

    // ── State transitions ────────────────────────────────────────────────────

    @Override
    @Transactional
    public SubscriptionResponseDTO cancelSubscription(Long merchantId, Long subscriptionId, boolean atPeriodEnd) {
        SubscriptionV2 sub = loadOrThrow(merchantId, subscriptionId);
        log.info("Cancelling subscription id={}, atPeriodEnd={}", subscriptionId, atPeriodEnd);

        if (sub.getStatus().isTerminal()) {
            throw SubscriptionException.alreadyCancelled(subscriptionId);
        }

        if (atPeriodEnd) {
            sub.setCancelAtPeriodEnd(true);
            // Status stays the same; billing engine will cancel at period end
        } else {
            SubscriptionStateMachine.assertTransition(sub.getStatus(), SubscriptionStatusV2.CANCELLED);
            sub.setStatus(SubscriptionStatusV2.CANCELLED);
            sub.setCancelledAt(LocalDateTime.now());
        }

        return mapper.toResponseDTO(subscriptionRepository.save(sub));
    }

    @Override
    @Transactional
    public SubscriptionResponseDTO pauseSubscription(Long merchantId, Long subscriptionId) {
        SubscriptionV2 sub = loadOrThrow(merchantId, subscriptionId);
        log.info("Pausing subscription id={}", subscriptionId);

        if (sub.getStatus() == SubscriptionStatusV2.PAUSED) {
            throw SubscriptionException.alreadyPaused(subscriptionId);
        }

        SubscriptionStateMachine.assertTransition(sub.getStatus(), SubscriptionStatusV2.PAUSED);
        sub.setStatus(SubscriptionStatusV2.PAUSED);
        sub.setPauseStartsAt(LocalDateTime.now());

        return mapper.toResponseDTO(subscriptionRepository.save(sub));
    }

    @Override
    @Transactional
    public SubscriptionResponseDTO resumeSubscription(Long merchantId, Long subscriptionId) {
        SubscriptionV2 sub = loadOrThrow(merchantId, subscriptionId);
        log.info("Resuming subscription id={}", subscriptionId);

        if (sub.getStatus() != SubscriptionStatusV2.PAUSED) {
            throw SubscriptionException.notPaused(subscriptionId);
        }

        SubscriptionStateMachine.assertTransition(sub.getStatus(), SubscriptionStatusV2.ACTIVE);
        sub.setStatus(SubscriptionStatusV2.ACTIVE);
        sub.setPauseEndsAt(LocalDateTime.now());

        return mapper.toResponseDTO(subscriptionRepository.save(sub));
    }

    @Override
    @Transactional(readOnly = true)
    public void validateSubscriptionBelongsToMerchant(Long merchantId, Long subscriptionId) {
        if (!subscriptionRepository.findByMerchantIdAndId(merchantId, subscriptionId).isPresent()) {
            throw SubscriptionException.notFound(merchantId, subscriptionId);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private SubscriptionV2 loadOrThrow(Long merchantId, Long subscriptionId) {
        return subscriptionRepository.findByMerchantIdAndId(merchantId, subscriptionId)
                .orElseThrow(() -> SubscriptionException.notFound(merchantId, subscriptionId));
    }

    private MerchantAccount loadMerchantOrThrow(Long merchantId) {
        return merchantAccountRepository.findById(merchantId)
                .orElseThrow(() -> MerchantException.merchantNotFound(merchantId));
    }

    private Customer loadCustomerOrThrow(Long merchantId, Long customerId) {
        return customerRepository.findByMerchantIdAndId(merchantId, customerId)
                .orElseThrow(() -> CustomerException.customerNotFound(merchantId, customerId));
    }

    private Product loadProductOrThrow(Long merchantId, Long productId) {
        return productRepository.findByMerchantIdAndId(merchantId, productId)
                .orElseThrow(() -> CatalogException.productNotFound(merchantId, productId));
    }

    private Price loadPriceOrThrow(Long merchantId, Long priceId) {
        return priceRepository.findByMerchantIdAndId(merchantId, priceId)
                .orElseThrow(() -> CatalogException.priceNotFound(merchantId, priceId));
    }

    private PriceVersion resolvePriceVersion(SubscriptionCreateRequestDTO request, Price price) {
        if (request.getPriceVersionId() != null) {
            return priceVersionRepository.findById(request.getPriceVersionId())
                    .filter(pv -> pv.getPrice().getId().equals(price.getId()))
                    .orElseThrow(() -> SubscriptionException.noPriceVersionAvailable(price.getId()));
        }
        // Resolve currently-effective version
        return priceVersionRepository
                .findTopByPriceIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        price.getId(), LocalDateTime.now())
                .orElseThrow(() -> SubscriptionException.noPriceVersionAvailable(price.getId()));
    }
}
