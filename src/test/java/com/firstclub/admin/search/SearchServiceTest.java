package com.firstclub.admin.search;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.customer.entity.Customer;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.customer.repository.CustomerRepository;
import com.firstclub.events.entity.DomainEvent;
import com.firstclub.events.repository.DomainEventRepository;
import com.firstclub.merchant.entity.MerchantAccount;
import com.firstclub.payments.entity.Payment;
import com.firstclub.payments.entity.PaymentIntentStatusV2;
import com.firstclub.payments.entity.PaymentIntentV2;
import com.firstclub.payments.entity.PaymentStatus;
import com.firstclub.payments.refund.entity.RefundV2;
import com.firstclub.payments.refund.entity.RefundV2Status;
import com.firstclub.payments.refund.repository.RefundV2Repository;
import com.firstclub.payments.repository.PaymentIntentV2Repository;
import com.firstclub.payments.repository.PaymentRepository;
import com.firstclub.subscription.entity.SubscriptionStatusV2;
import com.firstclub.subscription.entity.SubscriptionV2;
import com.firstclub.subscription.repository.SubscriptionV2Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SearchService}.
 *
 * Validates each search dimension individually, cross-merchant tenant isolation,
 * and the aggregated {@code search()} fan-out with query-type detection.
 * All tests are pure Mockito — no Spring context or DB required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService")
class SearchServiceTest {

    private static final Long   MERCHANT_ID       = 10L;
    private static final Long   OTHER_MERCHANT_ID = 99L;
    private static final LocalDateTime NOW         = LocalDateTime.of(2024, 6, 1, 12, 0);

    @Mock InvoiceRepository          invoiceRepository;
    @Mock PaymentRepository          paymentRepository;
    @Mock PaymentIntentV2Repository  paymentIntentV2Repository;
    @Mock RefundV2Repository         refundV2Repository;
    @Mock CustomerRepository         customerRepository;
    @Mock SubscriptionV2Repository   subscriptionV2Repository;
    @Mock DomainEventRepository      domainEventRepository;

    @InjectMocks SearchService searchService;

    // ══════════════════════════════════════════════════════════════════════
    // Invoice search
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("searchByInvoiceNumber()")
    class ByInvoiceNumber {

        @Test
        @DisplayName("returns invoice result when invoice exists for merchant")
        void found() {
            Invoice inv = new Invoice();
            inv.setId(1L);
            inv.setMerchantId(MERCHANT_ID);
            inv.setInvoiceNumber("INV-2024-000001");
            inv.setStatus(InvoiceStatus.PAID);
            inv.setCreatedAt(NOW);

            when(invoiceRepository.findByInvoiceNumberAndMerchantId("INV-2024-000001", MERCHANT_ID))
                    .thenReturn(Optional.of(inv));

            List<SearchResultDTO> results = searchService.searchByInvoiceNumber(MERCHANT_ID, "INV-2024-000001");

            assertThat(results).hasSize(1);
            SearchResultDTO r = results.get(0);
            assertThat(r.getResultType()).isEqualTo(SearchResultType.INVOICE);
            assertThat(r.getPrimaryId()).isEqualTo(1L);
            assertThat(r.getMerchantId()).isEqualTo(MERCHANT_ID);
            assertThat(r.getMatchedField()).isEqualTo("invoiceNumber");
            assertThat(r.getMatchedValue()).isEqualTo("INV-2024-000001");
            assertThat(r.getStatus()).isEqualTo("PAID");
            assertThat(r.getApiPath()).contains("/timeline/invoice/1");
        }

        @Test
        @DisplayName("returns empty list when invoice does not exist for this merchant")
        void notFound() {
            when(invoiceRepository.findByInvoiceNumberAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());

            assertThat(searchService.searchByInvoiceNumber(MERCHANT_ID, "INV-UNKNOWN")).isEmpty();
        }

        @Test
        @DisplayName("tenant isolation — absent for wrong merchant")
        void tenantIsolation() {
            when(invoiceRepository.findByInvoiceNumberAndMerchantId("INV-2024-000001", OTHER_MERCHANT_ID))
                    .thenReturn(Optional.empty());

            assertThat(searchService.searchByInvoiceNumber(OTHER_MERCHANT_ID, "INV-2024-000001")).isEmpty();
            verify(invoiceRepository).findByInvoiceNumberAndMerchantId("INV-2024-000001", OTHER_MERCHANT_ID);
        }

        @Test
        @DisplayName("no clientSecret field present in result")
        void noClientSecretLeakage() {
            Invoice inv = new Invoice();
            inv.setId(2L); inv.setMerchantId(MERCHANT_ID);
            inv.setInvoiceNumber("INV-2024-000002"); inv.setStatus(InvoiceStatus.OPEN);
            inv.setCreatedAt(NOW);
            when(invoiceRepository.findByInvoiceNumberAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.of(inv));

            SearchResultDTO r = searchService.searchByInvoiceNumber(MERCHANT_ID, "INV-2024-000002").get(0);
            // SearchResultDTO has no clientSecret field — verify via DTO field set
            assertThat(r).hasNoNullFieldsOrPropertiesExcept(); // all declared fields non-null except optional ones
            // Explicitly confirm the DTO does not expose a client secret
            assertThat(r.toString()).doesNotContain("clientSecret");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Gateway ref search
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("searchByGatewayRef()")
    class ByGatewayRef {

        @Test
        @DisplayName("matches a payment by gateway transaction ID")
        void matchesPayment() {
            Payment p = new Payment();
            p.setId(5L); p.setMerchantId(MERCHANT_ID);
            p.setGatewayTxnId("GW-TXN-12345");
            p.setStatus(PaymentStatus.CAPTURED);
            p.setCreatedAt(NOW);

            when(paymentRepository.findByGatewayTxnIdAndMerchantId("GW-TXN-12345", MERCHANT_ID))
                    .thenReturn(Optional.of(p));
            when(refundV2Repository.findByRefundReferenceAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());

            List<SearchResultDTO> results = searchService.searchByGatewayRef(MERCHANT_ID, "GW-TXN-12345");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getResultType()).isEqualTo(SearchResultType.PAYMENT);
            assertThat(results.get(0).getMatchedField()).isEqualTo("gatewayTxnId");
        }

        @Test
        @DisplayName("matches a refund by refund reference")
        void matchesRefund() {
            RefundV2 r = new RefundV2();
            r.setId(7L); r.setMerchantId(MERCHANT_ID);
            r.setRefundReference("RF-REF-99999");
            r.setStatus(RefundV2Status.COMPLETED);
            r.setCreatedAt(NOW);

            when(paymentRepository.findByGatewayTxnIdAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(refundV2Repository.findByRefundReferenceAndMerchantId("RF-REF-99999", MERCHANT_ID))
                    .thenReturn(Optional.of(r));

            List<SearchResultDTO> results = searchService.searchByGatewayRef(MERCHANT_ID, "RF-REF-99999");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getResultType()).isEqualTo(SearchResultType.REFUND);
            assertThat(results.get(0).getMatchedField()).isEqualTo("refundReference");
        }

        @Test
        @DisplayName("can return both a payment and a refund for the same ref string")
        void matchesBothPaymentAndRefund() {
            Payment p = new Payment();
            p.setId(10L); p.setMerchantId(MERCHANT_ID); p.setGatewayTxnId("SHARED");
            p.setStatus(PaymentStatus.CAPTURED); p.setCreatedAt(NOW);

            RefundV2 r = new RefundV2();
            r.setId(11L); r.setMerchantId(MERCHANT_ID); r.setRefundReference("SHARED");
            r.setStatus(RefundV2Status.COMPLETED); r.setCreatedAt(NOW);

            when(paymentRepository.findByGatewayTxnIdAndMerchantId("SHARED", MERCHANT_ID))
                    .thenReturn(Optional.of(p));
            when(refundV2Repository.findByRefundReferenceAndMerchantId("SHARED", MERCHANT_ID))
                    .thenReturn(Optional.of(r));

            assertThat(searchService.searchByGatewayRef(MERCHANT_ID, "SHARED")).hasSize(2);
        }

        @Test
        @DisplayName("tenant isolation — wrong merchant returns empty")
        void tenantIsolation() {
            when(paymentRepository.findByGatewayTxnIdAndMerchantId(any(), eq(OTHER_MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(refundV2Repository.findByRefundReferenceAndMerchantId(any(), eq(OTHER_MERCHANT_ID)))
                    .thenReturn(Optional.empty());

            assertThat(searchService.searchByGatewayRef(OTHER_MERCHANT_ID, "GW-TXN-12345")).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Correlation ID search
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("searchByCorrelationId()")
    class ByCorrelationId {

        @Test
        @DisplayName("returns all domain events for a correlation ID")
        void multipleEvents() {
            DomainEvent e1 = buildEvent(1L, "SUBSCRIPTION_CREATED", "corr-abc-123");
            DomainEvent e2 = buildEvent(2L, "INVOICE_CREATED",      "corr-abc-123");
            DomainEvent e3 = buildEvent(3L, "PAYMENT_INITIATED",    "corr-abc-123");

            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc("corr-abc-123", MERCHANT_ID))
                    .thenReturn(List.of(e1, e2, e3));

            List<SearchResultDTO> results = searchService.searchByCorrelationId(MERCHANT_ID, "corr-abc-123");

            assertThat(results).hasSize(3);
            assertThat(results).extracting(SearchResultDTO::getResultType)
                    .allMatch(t -> t == SearchResultType.DOMAIN_EVENT);
            assertThat(results).extracting(SearchResultDTO::getMatchedField)
                    .allMatch("correlationId"::equals);
        }

        @Test
        @DisplayName("returns empty list when no events match for merchant")
        void noMatch() {
            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc(any(), eq(MERCHANT_ID)))
                    .thenReturn(List.of());

            assertThat(searchService.searchByCorrelationId(MERCHANT_ID, "corr-unknown")).isEmpty();
        }

        @Test
        @DisplayName("tenant isolation — events for other merchant not returned")
        void tenantIsolation() {
            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc("corr-abc-123", OTHER_MERCHANT_ID))
                    .thenReturn(List.of());

            assertThat(searchService.searchByCorrelationId(OTHER_MERCHANT_ID, "corr-abc-123")).isEmpty();
            verify(domainEventRepository).findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc("corr-abc-123", OTHER_MERCHANT_ID);
        }

        @Test
        @DisplayName("event payload is never exposed in DTO")
        void noPayloadLeakage() {
            DomainEvent e = buildEvent(99L, "PAYMENT_INITIATED", "corr-x");
            e.setPayload("{\"clientSecret\":\"sk_test_SUPER_SECRET\"}");

            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc("corr-x", MERCHANT_ID))
                    .thenReturn(List.of(e));

            SearchResultDTO r = searchService.searchByCorrelationId(MERCHANT_ID, "corr-x").get(0);
            // DTO must not expose raw payload
            assertThat(r.toString()).doesNotContain("SUPER_SECRET");
            assertThat(r.toString()).doesNotContain("clientSecret");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Customer email search
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("searchByCustomerEmail()")
    class ByCustomerEmail {

        @Test
        @DisplayName("returns customer result for matching email")
        void found() {
            MerchantAccount merchant = new MerchantAccount();
            merchant.setId(MERCHANT_ID);

            Customer c = new Customer();
            c.setId(20L); c.setMerchant(merchant);
            c.setEmail("alice@example.com"); c.setStatus(CustomerStatus.ACTIVE);
            c.setCreatedAt(NOW);

            when(customerRepository.findByMerchantIdAndEmailIgnoreCase(MERCHANT_ID, "alice@example.com"))
                    .thenReturn(Optional.of(c));

            List<SearchResultDTO> results = searchService.searchByCustomerEmail(MERCHANT_ID, "alice@example.com");

            assertThat(results).hasSize(1);
            SearchResultDTO r = results.get(0);
            assertThat(r.getResultType()).isEqualTo(SearchResultType.CUSTOMER);
            assertThat(r.getPrimaryId()).isEqualTo(20L);
            assertThat(r.getMatchedField()).isEqualTo("email");
            assertThat(r.getDisplayLabel()).isEqualTo("alice@example.com");
            // Encrypted PII fields (phone, billing/shipping address) must not appear
            assertThat(r.toString()).doesNotContain("phone");
            assertThat(r.toString()).doesNotContain("billingAddress");
        }

        @Test
        @DisplayName("returns empty list when email not found for merchant")
        void notFound() {
            when(customerRepository.findByMerchantIdAndEmailIgnoreCase(eq(MERCHANT_ID), any()))
                    .thenReturn(Optional.empty());

            assertThat(searchService.searchByCustomerEmail(MERCHANT_ID, "unknown@example.com")).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Subscription ID search
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("searchBySubscriptionId()")
    class BySubscriptionId {

        @Test
        @DisplayName("returns subscription result when subscription exists")
        void found() {
            MerchantAccount merchant = new MerchantAccount();
            merchant.setId(MERCHANT_ID);

            SubscriptionV2 sub = new SubscriptionV2();
            sub.setId(30L); sub.setMerchant(merchant);
            sub.setStatus(SubscriptionStatusV2.ACTIVE); sub.setCreatedAt(NOW);

            when(subscriptionV2Repository.findByMerchantIdAndId(MERCHANT_ID, 30L))
                    .thenReturn(Optional.of(sub));

            List<SearchResultDTO> results = searchService.searchBySubscriptionId(MERCHANT_ID, 30L);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getResultType()).isEqualTo(SearchResultType.SUBSCRIPTION);
            assertThat(results.get(0).getPrimaryId()).isEqualTo(30L);
        }

        @Test
        @DisplayName("tenant isolation — subscription for other merchant returns empty")
        void tenantIsolation() {
            when(subscriptionV2Repository.findByMerchantIdAndId(OTHER_MERCHANT_ID, 30L))
                    .thenReturn(Optional.empty());

            assertThat(searchService.searchBySubscriptionId(OTHER_MERCHANT_ID, 30L)).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Payment Intent ID search
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("searchByPaymentIntentId()")
    class ByPaymentIntentId {

        @Test
        @DisplayName("returns payment intent result — never exposes clientSecret")
        void foundWithoutClientSecret() {
            MerchantAccount merchant = new MerchantAccount();
            merchant.setId(MERCHANT_ID);

            PaymentIntentV2 pi = new PaymentIntentV2();
            pi.setId(40L); pi.setMerchant(merchant);
            pi.setClientSecret("sk_test_TOP_SECRET_VALUE");
            pi.setStatus(PaymentIntentStatusV2.SUCCEEDED); pi.setCreatedAt(NOW);

            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, 40L))
                    .thenReturn(Optional.of(pi));

            List<SearchResultDTO> results = searchService.searchByPaymentIntentId(MERCHANT_ID, 40L);

            assertThat(results).hasSize(1);
            SearchResultDTO r = results.get(0);
            assertThat(r.getResultType()).isEqualTo(SearchResultType.PAYMENT_INTENT);
            // clientSecret must NOT appear anywhere in the result
            assertThat(r.toString()).doesNotContain("TOP_SECRET_VALUE");
            assertThat(r.toString()).doesNotContain("clientSecret");
        }

        @Test
        @DisplayName("tenant isolation — intent for wrong merchant returns empty")
        void tenantIsolation() {
            when(paymentIntentV2Repository.findByMerchantIdAndId(OTHER_MERCHANT_ID, 40L))
                    .thenReturn(Optional.empty());

            assertThat(searchService.searchByPaymentIntentId(OTHER_MERCHANT_ID, 40L)).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Event ID search
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("searchByEventId()")
    class ByEventId {

        @Test
        @DisplayName("returns event result for exact primary key match")
        void found() {
            DomainEvent e = buildEvent(50L, "SUBSCRIPTION_CREATED", "corr-xyz");
            when(domainEventRepository.findByIdAndMerchantId(50L, MERCHANT_ID))
                    .thenReturn(Optional.of(e));

            List<SearchResultDTO> results = searchService.searchByEventId(MERCHANT_ID, 50L);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getPrimaryId()).isEqualTo(50L);
            assertThat(results.get(0).getMatchedField()).isEqualTo("id");
        }

        @Test
        @DisplayName("tenant isolation — event for wrong merchant returns empty")
        void tenantIsolation() {
            when(domainEventRepository.findByIdAndMerchantId(50L, OTHER_MERCHANT_ID))
                    .thenReturn(Optional.empty());

            assertThat(searchService.searchByEventId(OTHER_MERCHANT_ID, 50L)).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Aggregated search() fan-out
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("search() aggregated fan-out")
    class AggregatedSearch {

        @Test
        @DisplayName("email query routes to customer search only (not numeric paths)")
        void emailQueryRoutesToCustomerSearch() {
            MerchantAccount merchant = new MerchantAccount();
            merchant.setId(MERCHANT_ID);
            Customer c = new Customer();
            c.setId(20L); c.setMerchant(merchant); c.setEmail("bob@test.com");
            c.setStatus(CustomerStatus.ACTIVE); c.setCreatedAt(NOW);

            when(customerRepository.findByMerchantIdAndEmailIgnoreCase(MERCHANT_ID, "bob@test.com"))
                    .thenReturn(Optional.of(c));
            // String lookups should still run but return empty
            when(invoiceRepository.findByInvoiceNumberAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(paymentRepository.findByGatewayTxnIdAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(refundV2Repository.findByRefundReferenceAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc(any(), eq(MERCHANT_ID)))
                    .thenReturn(List.of());

            List<SearchResultDTO> results = searchService.search(MERCHANT_ID, "bob@test.com");

            // Must include customer result
            assertThat(results).anySatisfy(r -> assertThat(r.getResultType()).isEqualTo(SearchResultType.CUSTOMER));
            // Numeric ID paths must NOT be called for an email query
            verify(subscriptionV2Repository, never()).findByMerchantIdAndId(any(), any());
            verify(paymentIntentV2Repository, never()).findByMerchantIdAndId(any(), any());
            verify(domainEventRepository, never()).findByIdAndMerchantId(any(), any());
        }

        @Test
        @DisplayName("numeric query fans out to subscription, payment intent, and event ID paths")
        void numericQueryFansOut() {
            long id = 42L;
            when(subscriptionV2Repository.findByMerchantIdAndId(MERCHANT_ID, id))
                    .thenReturn(Optional.empty());
            when(paymentIntentV2Repository.findByMerchantIdAndId(MERCHANT_ID, id))
                    .thenReturn(Optional.empty());
            when(domainEventRepository.findByIdAndMerchantId(id, MERCHANT_ID))
                    .thenReturn(Optional.empty());
            when(invoiceRepository.findByInvoiceNumberAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(paymentRepository.findByGatewayTxnIdAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(refundV2Repository.findByRefundReferenceAndMerchantId(any(), eq(MERCHANT_ID)))
                    .thenReturn(Optional.empty());
            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc(any(), eq(MERCHANT_ID)))
                    .thenReturn(List.of());

            searchService.search(MERCHANT_ID, "42");

            verify(subscriptionV2Repository).findByMerchantIdAndId(MERCHANT_ID, id);
            verify(paymentIntentV2Repository).findByMerchantIdAndId(MERCHANT_ID, id);
            verify(domainEventRepository).findByIdAndMerchantId(id, MERCHANT_ID);
        }

        @Test
        @DisplayName("empty result when nothing matches on any dimension")
        void emptyResultWhenNoMatch() {
            when(invoiceRepository.findByInvoiceNumberAndMerchantId(any(), any())).thenReturn(Optional.empty());
            when(paymentRepository.findByGatewayTxnIdAndMerchantId(any(), any())).thenReturn(Optional.empty());
            when(refundV2Repository.findByRefundReferenceAndMerchantId(any(), any())).thenReturn(Optional.empty());
            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

            assertThat(searchService.search(MERCHANT_ID, "INV-NOTHING")).isEmpty();
        }

        @Test
        @DisplayName("negative or zero numeric string is treated as non-numeric")
        void nonPositiveNumericTreatedAsString() {
            when(invoiceRepository.findByInvoiceNumberAndMerchantId(any(), any())).thenReturn(Optional.empty());
            when(paymentRepository.findByGatewayTxnIdAndMerchantId(any(), any())).thenReturn(Optional.empty());
            when(refundV2Repository.findByRefundReferenceAndMerchantId(any(), any())).thenReturn(Optional.empty());
            when(domainEventRepository.findByCorrelationIdAndMerchantIdOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

            searchService.search(MERCHANT_ID, "0");

            // Numeric ID paths must NOT be called for zero (non-positive)
            verify(subscriptionV2Repository, never()).findByMerchantIdAndId(any(), any());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private DomainEvent buildEvent(Long id, String eventType, String correlationId) {
        DomainEvent e = new DomainEvent();
        e.setId(id);
        e.setMerchantId(MERCHANT_ID);
        e.setEventType(eventType);
        e.setCorrelationId(correlationId);
        e.setAggregateType("SUBSCRIPTION");
        e.setAggregateId("sub-1");
        e.setCreatedAt(NOW);
        return e;
    }
}
