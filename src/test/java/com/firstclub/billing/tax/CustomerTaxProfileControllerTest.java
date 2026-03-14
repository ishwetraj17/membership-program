package com.firstclub.billing.tax;

import com.firstclub.billing.tax.dto.*;
import com.firstclub.billing.tax.entity.*;
import com.firstclub.billing.tax.repository.CustomerTaxProfileRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CustomerTaxProfileControllerTest extends PostgresIntegrationTestBase {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private CustomerTaxProfileRepository customerTaxProfileRepository;

    private static final long MERCHANT_ID  = 4001L;
    private static final long CUSTOMER_ID  = 5001L;

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

    private String profileUrl() {
        return "/api/v2/merchants/" + MERCHANT_ID + "/customers/" + CUSTOMER_ID + "/tax-profile";
    }

    @BeforeEach
    void cleanup() {
        customerTaxProfileRepository.findByCustomerId(CUSTOMER_ID)
                .ifPresent(customerTaxProfileRepository::delete);
    }

    // ── Test 1: POST creates profile ──────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /tax-profile — creates customer tax profile")
    void createCustomerProfile_validRequest_returns200() {
        CustomerTaxProfileCreateOrUpdateRequestDTO req =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .gstin("27BBBBB1111B1Z5").stateCode("MH")
                        .entityType(CustomerEntityType.BUSINESS).taxExempt(false).build();

        ResponseEntity<CustomerTaxProfileResponseDTO> resp =
                restTemplate.postForEntity(profileUrl(), req, CustomerTaxProfileResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(resp.getBody().getStateCode()).isEqualTo("MH");
        assertThat(resp.getBody().isTaxExempt()).isFalse();
    }

    // ── Test 2: tax-exempt individual ─────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("POST /tax-profile — tax-exempt individual profile saved")
    void createCustomerProfile_taxExempt_flagPersisted() {
        CustomerTaxProfileCreateOrUpdateRequestDTO req =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .stateCode("KA")
                        .entityType(CustomerEntityType.INDIVIDUAL).taxExempt(true).build();

        ResponseEntity<CustomerTaxProfileResponseDTO> resp =
                restTemplate.postForEntity(profileUrl(), req, CustomerTaxProfileResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().isTaxExempt()).isTrue();
        assertThat(resp.getBody().getEntityType()).isEqualTo(CustomerEntityType.INDIVIDUAL);
        assertThat(resp.getBody().getGstin()).isNull();
    }

    // ── Test 3: GET returns profile ───────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /tax-profile — returns existing customer profile")
    void getCustomerProfile_exists_returns200() {
        CustomerTaxProfileCreateOrUpdateRequestDTO req =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .stateCode("MH").entityType(CustomerEntityType.BUSINESS)
                        .taxExempt(false).build();
        restTemplate.postForEntity(profileUrl(), req, CustomerTaxProfileResponseDTO.class);

        ResponseEntity<CustomerTaxProfileResponseDTO> resp =
                restTemplate.getForEntity(profileUrl(), CustomerTaxProfileResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getCustomerId()).isEqualTo(CUSTOMER_ID);
    }

    // ── Test 4: GET non-existing → 404 ───────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /tax-profile — customer has no profile returns 4xx")
    void getCustomerProfile_notExists_returns4xx() {
        ResponseEntity<String> resp =
                restTemplate.getForEntity(profileUrl(), String.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    // ── Test 5: POST missing stateCode → 400 ─────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /tax-profile — missing stateCode returns 400")
    void createCustomerProfile_missingStateCode_returns400() {
        CustomerTaxProfileCreateOrUpdateRequestDTO req =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .entityType(CustomerEntityType.INDIVIDUAL).taxExempt(false).build();

        ResponseEntity<String> resp =
                restTemplate.postForEntity(profileUrl(), req, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Test 6: second POST upserts ───────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("POST /tax-profile — second POST updates customer profile in place")
    void createCustomerProfile_secondPost_upserts() {
        CustomerTaxProfileCreateOrUpdateRequestDTO first =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .stateCode("MH").entityType(CustomerEntityType.INDIVIDUAL)
                        .taxExempt(false).build();
        restTemplate.postForEntity(profileUrl(), first, CustomerTaxProfileResponseDTO.class);

        CustomerTaxProfileCreateOrUpdateRequestDTO second =
                CustomerTaxProfileCreateOrUpdateRequestDTO.builder()
                        .stateCode("KA").entityType(CustomerEntityType.BUSINESS)
                        .taxExempt(true).build();
        ResponseEntity<CustomerTaxProfileResponseDTO> resp =
                restTemplate.postForEntity(profileUrl(), second, CustomerTaxProfileResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getStateCode()).isEqualTo("KA");
        assertThat(resp.getBody().isTaxExempt()).isTrue();
        assertThat(customerTaxProfileRepository.findAll().stream()
                .filter(p -> p.getCustomerId().equals(CUSTOMER_ID)).count()).isEqualTo(1);
    }
}
