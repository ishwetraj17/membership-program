package com.firstclub.billing.tax;

import com.firstclub.billing.tax.dto.*;
import com.firstclub.billing.tax.entity.*;
import com.firstclub.billing.tax.repository.TaxProfileRepository;
import com.firstclub.membership.PostgresIntegrationTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MerchantTaxProfileControllerTest extends PostgresIntegrationTestBase {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TaxProfileRepository taxProfileRepository;

    private static final long MERCHANT_ID = 3001L;

    private String profileUrl() {
        return "/api/v2/merchants/" + MERCHANT_ID + "/tax-profile";
    }

    @BeforeEach
    void cleanup() {
        taxProfileRepository.findByMerchantId(MERCHANT_ID).ifPresent(taxProfileRepository::delete);
    }

    // ── Test 1: POST creates profile ──────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /tax-profile — creates merchant tax profile, returns 200")
    void createMerchantProfile_validRequest_returns200() {
        TaxProfileCreateOrUpdateRequestDTO req = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("First Club Pvt Ltd").taxMode(TaxMode.B2B).build();

        ResponseEntity<TaxProfileResponseDTO> resp =
                restTemplate.postForEntity(profileUrl(), req, TaxProfileResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(resp.getBody().getGstin()).isEqualTo("27AAAAA0000A1Z5");
        assertThat(resp.getBody().getLegalStateCode()).isEqualTo("MH");
        assertThat(resp.getBody().getTaxMode()).isEqualTo(TaxMode.B2B);
    }

    // ── Test 2: POST again updates in place ───────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("POST /tax-profile — second POST updates existing profile")
    void createMerchantProfile_secondPost_upserts() {
        TaxProfileCreateOrUpdateRequestDTO first = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("Old Name").taxMode(TaxMode.B2C).build();
        restTemplate.postForEntity(profileUrl(), first, TaxProfileResponseDTO.class);

        TaxProfileCreateOrUpdateRequestDTO second = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("New Name").taxMode(TaxMode.B2B).build();
        ResponseEntity<TaxProfileResponseDTO> resp =
                restTemplate.postForEntity(profileUrl(), second, TaxProfileResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getRegisteredBusinessName()).isEqualTo("New Name");
        assertThat(resp.getBody().getTaxMode()).isEqualTo(TaxMode.B2B);
        assertThat(taxProfileRepository.findAll().stream()
                .filter(p -> p.getMerchantId().equals(MERCHANT_ID)).count()).isEqualTo(1);
    }

    // ── Test 3: GET returns existing profile ──────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /tax-profile — returns existing profile")
    void getMerchantProfile_exists_returns200() {
        TaxProfileCreateOrUpdateRequestDTO req = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("First Club Pvt Ltd").taxMode(TaxMode.B2B).build();
        restTemplate.postForEntity(profileUrl(), req, TaxProfileResponseDTO.class);

        ResponseEntity<TaxProfileResponseDTO> resp =
                restTemplate.getForEntity(profileUrl(), TaxProfileResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getMerchantId()).isEqualTo(MERCHANT_ID);
    }

    // ── Test 4: GET non-existing → 404 ───────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /tax-profile — merchant has no profile returns 4xx")
    void getMerchantProfile_notExists_returns4xx() {
        ResponseEntity<String> resp =
                restTemplate.getForEntity(profileUrl(), String.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
    }

    // ── Test 5: POST invalid GSTIN → 400 ─────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /tax-profile — invalid GSTIN returns 400")
    void createMerchantProfile_invalidGstin_returns400() {
        TaxProfileCreateOrUpdateRequestDTO req = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("INVALID").legalStateCode("MH")
                .registeredBusinessName("Test Corp").taxMode(TaxMode.B2B).build();

        ResponseEntity<String> resp =
                restTemplate.postForEntity(profileUrl(), req, String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Test 6: PUT updates profile ───────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("PUT /tax-profile — updates existing profile")
    void putMerchantProfile_existing_updates() {
        TaxProfileCreateOrUpdateRequestDTO create = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("Initial Name").taxMode(TaxMode.B2C).build();
        restTemplate.postForEntity(profileUrl(), create, TaxProfileResponseDTO.class);

        TaxProfileCreateOrUpdateRequestDTO update = TaxProfileCreateOrUpdateRequestDTO.builder()
                .gstin("27AAAAA0000A1Z5").legalStateCode("MH")
                .registeredBusinessName("Updated Name").taxMode(TaxMode.B2B).build();
        restTemplate.put(profileUrl(), update);

        ResponseEntity<TaxProfileResponseDTO> resp =
                restTemplate.getForEntity(profileUrl(), TaxProfileResponseDTO.class);
        assertThat(resp.getBody().getRegisteredBusinessName()).isEqualTo("Updated Name");
    }
}
