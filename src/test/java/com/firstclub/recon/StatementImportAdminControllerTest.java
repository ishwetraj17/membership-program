package com.firstclub.recon;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.merchant.dto.MerchantCreateRequestDTO;
import com.firstclub.merchant.dto.MerchantResponseDTO;
import com.firstclub.recon.dto.StatementImportRequestDTO;
import com.firstclub.recon.entity.StatementSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.recon.controller.StatementImportAdminController}.
 */
@DisplayName("StatementImportAdminController — Integration Tests")
class StatementImportAdminControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String adminToken;
    private Long   merchantId;

    @BeforeEach
    void setUp() {
        adminToken = login();
        merchantId = createMerchant();
    }

    // ── POST / ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST / — 200 with valid CSV → IMPORTED status")
    void importStatement_validCsv_imported() {
        String csv = "txn_id,amount,currency,payment_date,reference\n"
                   + "TXN-001,1000.00,INR,2024-01-01,PAY-001\n"
                   + "TXN-002,2500.50,INR,2024-01-01,PAY-002\n";

        StatementImportRequestDTO req = StatementImportRequestDTO.builder()
                .merchantId(merchantId)
                .sourceType(StatementSourceType.GATEWAY)
                .statementDate(LocalDate.of(2024, 1, 1))
                .fileName("stripe-2024-01-01.csv")
                .csvContent(csv)
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/import-statement",
                HttpMethod.POST, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("id");
        assertThat(resp.getBody().get("status")).isEqualTo("IMPORTED");
        assertThat(resp.getBody().get("rowCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("POST / — 200 with empty CSV → IMPORTED status, 0 rows")
    void importStatement_emptyCsv_zeroRows() {
        StatementImportRequestDTO req = StatementImportRequestDTO.builder()
                .merchantId(merchantId)
                .sourceType(StatementSourceType.BANK)
                .statementDate(LocalDate.of(2024, 1, 2))
                .fileName("bank-empty.csv")
                .csvContent("")
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/import-statement",
                HttpMethod.POST, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("rowCount")).isEqualTo(0);
        assertThat(resp.getBody().get("status")).isEqualTo("IMPORTED");
    }

    @Test
    @DisplayName("POST / — 200 with malformed CSV → FAILED status")
    void importStatement_malformedCsv_failed() {
        StatementImportRequestDTO req = StatementImportRequestDTO.builder()
                .merchantId(merchantId)
                .sourceType(StatementSourceType.GATEWAY)
                .statementDate(LocalDate.of(2024, 1, 3))
                .fileName("bad.csv")
                .csvContent("txn_id,amount\nBAD,100\n")
                .build();

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/import-statement",
                HttpMethod.POST, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("POST / — 401 without auth")
    void importStatement_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/import-statement",
                HttpMethod.POST, new HttpEntity<>(json()), new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── GET / ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET / — 200 with pagination for merchant")
    void listImports_returnsPaginatedResults() {
        // Create an import first
        importCsv(LocalDate.of(2024, 2, 1));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/import-statement?merchantId=" + merchantId,
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("content");
    }

    // ── GET /{importId} ───────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /{importId} — 200 returns the import")
    void getImport_existsReturns200() {
        Long importId = importCsv(LocalDate.of(2024, 3, 1));

        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/import-statement/" + importId,
                HttpMethod.GET, new HttpEntity<>(auth()), new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) resp.getBody().get("id")).longValue()).isEqualTo(importId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders auth() {
        HttpHeaders h = json();
        h.setBearerAuth(adminToken);
        return h;
    }

    private String login() {
        ResponseEntity<JwtResponseDTO> resp = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), json()),
                JwtResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().getToken();
    }

    private Long createMerchant() {
        MerchantCreateRequestDTO req = MerchantCreateRequestDTO.builder()
                .merchantCode("SI_" + System.nanoTime())
                .legalName("Statement Import Merchant")
                .displayName("Statement Import Test")
                .supportEmail("si@test.com")
                .defaultCurrency("INR")
                .countryCode("IN")
                .timezone("Asia/Kolkata")
                .build();
        ResponseEntity<MerchantResponseDTO> resp = rest.exchange(
                base() + "/api/v2/admin/merchants",
                HttpMethod.POST, new HttpEntity<>(req, auth()), MerchantResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().getId();
    }

    private Long importCsv(LocalDate date) {
        String csv = "txn_id,amount,currency,payment_date,reference\n"
                   + "TXN-A,500.00,INR," + date + ",P-A\n";
        StatementImportRequestDTO req = StatementImportRequestDTO.builder()
                .merchantId(merchantId)
                .sourceType(StatementSourceType.GATEWAY)
                .statementDate(date)
                .fileName("test-" + date + ".csv")
                .csvContent(csv)
                .build();
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/recon/import-statement",
                HttpMethod.POST, new HttpEntity<>(req, auth()), new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) resp.getBody().get("id")).longValue();
    }
}
