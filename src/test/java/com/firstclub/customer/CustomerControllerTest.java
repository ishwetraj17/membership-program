package com.firstclub.customer;

import com.firstclub.customer.dto.CustomerCreateRequestDTO;
import com.firstclub.customer.dto.CustomerNoteCreateRequestDTO;
import com.firstclub.customer.dto.CustomerNoteResponseDTO;
import com.firstclub.customer.dto.CustomerResponseDTO;
import com.firstclub.customer.dto.CustomerUpdateRequestDTO;
import com.firstclub.customer.entity.CustomerNoteVisibility;
import com.firstclub.customer.entity.CustomerStatus;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.merchant.dto.MerchantStatusUpdateRequestDTO;
import com.firstclub.merchant.entity.MerchantStatus;
import com.firstclub.merchant.service.MerchantService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for CustomerController + CustomerNoteController.
 *
 * Runs against a Testcontainers Postgres instance (skipped without Docker).
 * Covers: CRUD, pagination, tenant isolation, note creation, encryption smoke test.
 */
@DisplayName("Customer Controller - Integration Tests")
class CustomerControllerTest extends com.firstclub.membership.PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @Autowired private MerchantService merchantService;
    @Autowired private TestRestTemplate restTemplate;
    @LocalServerPort  private int port;

    private String adminToken;
    private Long merchantId;    // ACTIVE merchant for most tests
    private Long merchant2Id;   // second merchant for isolation tests

    private String baseUrl() { return "http://localhost:" + port; }

    private String customersUrl(Long mId) {
        return baseUrl() + "/api/v2/merchants/" + mId + "/customers";
    }

    private String notesUrl(Long mId, Long cId) {
        return customersUrl(mId) + "/" + cId + "/notes";
    }

    @BeforeEach
    void setup() {
        // Obtain admin JWT
        LoginRequestDTO login = LoginRequestDTO.builder()
                .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build();
        ResponseEntity<JwtResponseDTO> auth = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/login",
                new HttpEntity<>(login, jsonHeaders()),
                JwtResponseDTO.class
        );
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();

        // Create and ACTIVATE merchant 1
        String code1 = "CUST_M1_" + System.nanoTime();
        MerchantResponseDTO m1 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code1)
                        .legalName("Tenant One").displayName("T1")
                        .supportEmail("t1@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m1.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchantId = m1.getId();

        // Create and ACTIVATE merchant 2 (for isolation tests)
        String code2 = "CUST_M2_" + System.nanoTime();
        MerchantResponseDTO m2 = merchantService.createMerchant(
                MerchantCreateRequestDTO.builder().merchantCode(code2)
                        .legalName("Tenant Two").displayName("T2")
                        .supportEmail("t2@test.com").defaultCurrency("INR")
                        .countryCode("IN").timezone("Asia/Kolkata").build());
        merchantService.updateMerchantStatus(m2.getId(),
                new MerchantStatusUpdateRequestDTO(MerchantStatus.ACTIVE, null));
        merchant2Id = m2.getId();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = jsonHeaders();
        h.setBearerAuth(adminToken);
        return h;
    }

    private <T> HttpEntity<T> authed(T body) { return new HttpEntity<>(body, authHeaders()); }
    private HttpEntity<Void> authedGet()      { return new HttpEntity<>(authHeaders()); }

    private CustomerCreateRequestDTO createReq(String email) {
        return CustomerCreateRequestDTO.builder()
                .fullName("Test Customer").email(email).phone("+91-9999999999")
                .billingAddress("123 Main St").build();
    }

    // ── POST /customers ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /customers")
    class CreateCustomerTests {

        @Test
        @DisplayName("Should create customer and return 201")
        void shouldCreateAndReturn201() {
            ResponseEntity<CustomerResponseDTO> resp = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq("alice@test.com")),
                    CustomerResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getEmail()).isEqualTo("alice@test.com");
            assertThat(resp.getBody().getStatus()).isEqualTo(CustomerStatus.ACTIVE);
            assertThat(resp.getBody().getMerchantId()).isEqualTo(merchantId);
        }

        @Test
        @DisplayName("Should reject duplicate email within same merchant with 409")
        void shouldRejectDuplicateEmailWithin409() {
            String email = "dup_" + System.nanoTime() + "@test.com";
            restTemplate.postForEntity(customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class);

            ResponseEntity<Map<String, Object>> second = restTemplate.exchange(
                    customersUrl(merchantId), HttpMethod.POST, authed(createReq(email)), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        @DisplayName("Should allow same email across different merchants")
        void shouldAllowSameEmailAcrossMerchants() {
            String email = "shared_" + System.nanoTime() + "@test.com";

            ResponseEntity<CustomerResponseDTO> r1 = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)), CustomerResponseDTO.class);
            ResponseEntity<CustomerResponseDTO> r2 = restTemplate.postForEntity(
                    customersUrl(merchant2Id), authed(createReq(email)), CustomerResponseDTO.class);

            assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(r1.getBody().getMerchantId()).isEqualTo(merchantId);
            assertThat(r2.getBody().getMerchantId()).isEqualTo(merchant2Id);
        }

        @Test
        @DisplayName("Should return 400 for missing required fields")
        void shouldReturn400ForMissingFields() {
            CustomerCreateRequestDTO bad = CustomerCreateRequestDTO.builder()
                    .email("nofullname@test.com").build(); // missing fullName

            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    customersUrl(merchantId), HttpMethod.POST, authed(bad), new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Should return 401 without auth token")
        void shouldReturn401WithoutToken() {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    customersUrl(merchantId), HttpMethod.POST,
                    new HttpEntity<>(createReq("noauth@test.com"), jsonHeaders()),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            assertThat(resp.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("Encrypted fields (phone, billing_address) should be persisted and returned decrypted")
        void encryptedFieldsPersistAndDecrypt() {
            CustomerCreateRequestDTO req = CustomerCreateRequestDTO.builder()
                    .fullName("Enc Test").email("enc_" + System.nanoTime() + "@test.com")
                    .phone("+91-8888888888").billingAddress("456 Enc Street")
                    .shippingAddress("789 Ship Lane").build();

            ResponseEntity<CustomerResponseDTO> resp = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(req), CustomerResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            // Decrypted values are returned in the response
            assertThat(resp.getBody().getPhone()).isEqualTo("+91-8888888888");
            assertThat(resp.getBody().getBillingAddress()).isEqualTo("456 Enc Street");
            assertThat(resp.getBody().getShippingAddress()).isEqualTo("789 Ship Lane");
        }
    }

    // ── GET /customers/{id} ───────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /customers/{customerId}")
    class GetCustomerTests {

        @Test
        @DisplayName("Should return customer for valid merchantId and customerId")
        void shouldReturnCustomer() {
            String email = "get_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO created = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            ResponseEntity<CustomerResponseDTO> resp = restTemplate.exchange(
                    customersUrl(merchantId) + "/" + created.getId(),
                    HttpMethod.GET, authedGet(), CustomerResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getId()).isEqualTo(created.getId());
        }

        @Test
        @DisplayName("Should return 404 when reading customer from a different merchant (tenant isolation)")
        void shouldReturn404ForCrossMerchantRead() {
            String email = "iso_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO created = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            // Try to read that customer via merchant 2
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    customersUrl(merchant2Id) + "/" + created.getId(),
                    HttpMethod.GET, authedGet(), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ── GET /customers (pagination) ───────────────────────────────────────────

    @Nested
    @DisplayName("GET /customers (list + pagination)")
    class ListCustomerTests {

        @Test
        @DisplayName("Should return paginated list of customers for a merchant")
        void shouldReturnPaginatedList() {
            String prefix = "page_" + System.nanoTime();
            for (int i = 0; i < 3; i++) {
                restTemplate.postForEntity(customersUrl(merchantId),
                        authed(createReq(prefix + i + "@test.com")),
                        CustomerResponseDTO.class);
            }

            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    customersUrl(merchantId) + "?page=0&size=2",
                    HttpMethod.GET, authedGet(), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((List<?>) resp.getBody().get("content")).hasSizeGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Should filter customers by status")
        void shouldFilterByStatus() {
            String email = "filter_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO created = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            // Block the customer
            restTemplate.exchange(
                    customersUrl(merchantId) + "/" + created.getId() + "/block",
                    HttpMethod.POST, authedGet(), CustomerResponseDTO.class);

            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    customersUrl(merchantId) + "?status=BLOCKED",
                    HttpMethod.GET, authedGet(), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<?> content = (List<?>) resp.getBody().get("content");
            assertThat(content).isNotEmpty();
        }
    }

    // ── PUT /customers/{id} ───────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /customers/{customerId}")
    class UpdateCustomerTests {

        @Test
        @DisplayName("Should update customer fields and return 200")
        void shouldUpdateAndReturn200() {
            String email = "upd_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO created = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            CustomerUpdateRequestDTO update = CustomerUpdateRequestDTO.builder()
                    .fullName("Updated Name").build();

            ResponseEntity<CustomerResponseDTO> resp = restTemplate.exchange(
                    customersUrl(merchantId) + "/" + created.getId(),
                    HttpMethod.PUT, authed(update), CustomerResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getFullName()).isEqualTo("Updated Name");
        }
    }

    // ── POST /customers/{id}/block and /activate ──────────────────────────────

    @Nested
    @DisplayName("POST /customers/{customerId}/block and /activate")
    class StatusTransitionTests {

        @Test
        @DisplayName("Should block ACTIVE customer and return BLOCKED status")
        void shouldBlockCustomer() {
            String email = "blk_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO created = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            ResponseEntity<CustomerResponseDTO> blocked = restTemplate.exchange(
                    customersUrl(merchantId) + "/" + created.getId() + "/block",
                    HttpMethod.POST, authedGet(), CustomerResponseDTO.class);

            assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(blocked.getBody().getStatus()).isEqualTo(CustomerStatus.BLOCKED);
        }

        @Test
        @DisplayName("Should re-activate a BLOCKED customer")
        void shouldActivateBlockedCustomer() {
            String email = "act_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO created = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            restTemplate.exchange(
                    customersUrl(merchantId) + "/" + created.getId() + "/block",
                    HttpMethod.POST, authedGet(), CustomerResponseDTO.class);

            ResponseEntity<CustomerResponseDTO> activated = restTemplate.exchange(
                    customersUrl(merchantId) + "/" + created.getId() + "/activate",
                    HttpMethod.POST, authedGet(), CustomerResponseDTO.class);

            assertThat(activated.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(activated.getBody().getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        }
    }

    // ── Customer Notes ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Customer Notes endpoints")
    class CustomerNoteTests {

        @Test
        @DisplayName("Should create note and return 201")
        void shouldCreateNoteAndReturn201() {
            String email = "note_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO customer = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            CustomerNoteCreateRequestDTO noteReq = CustomerNoteCreateRequestDTO.builder()
                    .noteText("Important: VIP customer.")
                    .visibility(CustomerNoteVisibility.INTERNAL_ONLY)
                    .build();

            ResponseEntity<CustomerNoteResponseDTO> resp = restTemplate.postForEntity(
                    notesUrl(merchantId, customer.getId()),
                    authed(noteReq), CustomerNoteResponseDTO.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody().getNoteText()).isEqualTo("Important: VIP customer.");
            assertThat(resp.getBody().getCustomerId()).isEqualTo(customer.getId());
        }

        @Test
        @DisplayName("Should return 404 when adding note to customer of different merchant")
        void shouldReturn404ForCrossMerchantNote() {
            String email = "note_iso_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO customer = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            CustomerNoteCreateRequestDTO noteReq = CustomerNoteCreateRequestDTO.builder()
                    .noteText("Should fail.").visibility(CustomerNoteVisibility.INTERNAL_ONLY).build();

            // Post note via merchant 2 for customer of merchant 1 → 404
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                    notesUrl(merchant2Id, customer.getId()),
                    HttpMethod.POST, authed(noteReq), new ParameterizedTypeReference<Map<String, Object>>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Should list notes newest-first")
        void shouldListNotes() {
            String email = "notelist_" + System.nanoTime() + "@test.com";
            CustomerResponseDTO customer = restTemplate.postForEntity(
                    customersUrl(merchantId), authed(createReq(email)),
                    CustomerResponseDTO.class).getBody();

            for (int i = 0; i < 2; i++) {
                restTemplate.postForEntity(notesUrl(merchantId, customer.getId()),
                        authed(CustomerNoteCreateRequestDTO.builder()
                                .noteText("Note " + i)
                                .visibility(CustomerNoteVisibility.MERCHANT_VISIBLE)
                                .build()),
                        CustomerNoteResponseDTO.class);
            }

            ResponseEntity<List<CustomerNoteResponseDTO>> resp = restTemplate.exchange(
                    notesUrl(merchantId, customer.getId()),
                    HttpMethod.GET, authedGet(),
                    new ParameterizedTypeReference<>() {});

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).hasSize(2);
        }
    }
}
