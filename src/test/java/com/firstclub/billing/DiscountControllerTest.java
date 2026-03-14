package com.firstclub.billing;

import com.firstclub.billing.dto.DiscountCreateRequestDTO;
import com.firstclub.billing.dto.DiscountResponseDTO;
import com.firstclub.billing.entity.DiscountStatus;
import com.firstclub.billing.entity.DiscountType;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.billing.controller.DiscountController}.
 *
 * <p>No merchant account setup is required because {@code Discount.merchantId} is
 * a plain Long column (no DB-level FK in the JPA create-drop schema), letting us
 * use any arbitrary merchantId value.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiscountControllerTest extends PostgresIntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    private static final long MERCHANT_ID = 1001L;
    private Long createdDiscountId;

    @BeforeAll
    void authenticate() {
        LoginRequestDTO login = LoginRequestDTO.builder()
                .email("admin@firstclub.com").password("Admin@firstclub1").build();
        ResponseEntity<JwtResponseDTO> auth = restTemplate.postForEntity(
                "/api/v1/auth/login", login, JwtResponseDTO.class);
        restTemplate.getRestTemplate().getInterceptors().add(
                (request, body, execution) -> {
                    request.getHeaders().setBearerAuth(auth.getBody().getToken());
                    return execution.execute(request, body);
                });
    }

    private String baseUrl() {
        return "/api/v2/merchants/" + MERCHANT_ID + "/discounts";
    }

    private DiscountCreateRequestDTO validFixedRequest(String code) {
        return DiscountCreateRequestDTO.builder()
                .code(code)
                .discountType(DiscountType.FIXED)
                .value(new BigDecimal("100.00"))
                .currency("INR")
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build();
    }

    // ── Test 1: Create discount returns 201 ───────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /discounts — valid request returns 201 and ACTIVE status")
    void createDiscount_validRequest_returns201() {
        ResponseEntity<DiscountResponseDTO> resp = restTemplate.postForEntity(
                baseUrl(), validFixedRequest("WELCOME100"), DiscountResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo("WELCOME100");
        assertThat(resp.getBody().getStatus()).isEqualTo(DiscountStatus.ACTIVE);
        assertThat(resp.getBody().getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(resp.getBody().getDiscountType()).isEqualTo(DiscountType.FIXED);

        createdDiscountId = resp.getBody().getId();
    }

    // ── Test 2: List discounts returns the created one ────────────────────────

    @Test
    @Order(2)
    @DisplayName("GET /discounts — returns list containing the created discount")
    void listDiscounts_afterCreate_containsDiscount() {
        // ensure discount exists first
        restTemplate.postForEntity(baseUrl(), validFixedRequest("LIST_TEST"), DiscountResponseDTO.class);

        ResponseEntity<List<DiscountResponseDTO>> resp = restTemplate.exchange(
                baseUrl(), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody())
                .anyMatch(d -> d.getMerchantId().equals(MERCHANT_ID));
    }

    // ── Test 3: Get discount by ID ────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /discounts/{id} — returns correct discount")
    void getDiscount_byId_returnsDiscount() {
        ResponseEntity<DiscountResponseDTO> created = restTemplate.postForEntity(
                baseUrl(), validFixedRequest("GET_BY_ID_TEST"), DiscountResponseDTO.class);
        Long id = created.getBody().getId();

        ResponseEntity<DiscountResponseDTO> resp = restTemplate.getForEntity(
                baseUrl() + "/" + id, DiscountResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getId()).isEqualTo(id);
        assertThat(resp.getBody().getCode()).isEqualTo("GET_BY_ID_TEST");
    }

    // ── Test 4: Duplicate code returns error ──────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("POST /discounts — duplicate code for same merchant returns 5xx/4xx")
    void createDiscount_duplicateCode_returnsError() {
        restTemplate.postForEntity(baseUrl(), validFixedRequest("DUPE"), DiscountResponseDTO.class);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                baseUrl(), validFixedRequest("DUPE"), String.class);

        assertThat(resp.getStatusCode().is4xxClientError()
                || resp.getStatusCode().is5xxServerError()).isTrue();
    }

    // ── Test 5: PERCENTAGE 0-100 validation ──────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /discounts — PERCENTAGE > 100 returns error")
    void createDiscount_percentageOver100_returnsError() {
        DiscountCreateRequestDTO req = DiscountCreateRequestDTO.builder()
                .code("BADPCT")
                .discountType(DiscountType.PERCENTAGE)
                .value(new BigDecimal("150"))
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build();

        ResponseEntity<String> resp = restTemplate.postForEntity(baseUrl(), req, String.class);

        assertThat(resp.getStatusCode().is4xxClientError()
                || resp.getStatusCode().is5xxServerError()).isTrue();
    }

    // ── Test 6: Valid PERCENTAGE discount ────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("POST /discounts — valid PERCENTAGE discount creates successfully")
    void createDiscount_validPercentage_returns201() {
        DiscountCreateRequestDTO req = DiscountCreateRequestDTO.builder()
                .code("PERCENT10")
                .discountType(DiscountType.PERCENTAGE)
                .value(new BigDecimal("10"))
                .validFrom(LocalDateTime.now().minusDays(1))
                .validTo(LocalDateTime.now().plusDays(30))
                .build();

        ResponseEntity<DiscountResponseDTO> resp = restTemplate.postForEntity(
                baseUrl(), req, DiscountResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().getDiscountType()).isEqualTo(DiscountType.PERCENTAGE);
    }
}
