package com.firstclub.reporting.projections;

import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.reporting.projections.dto.LedgerBalanceSnapshotDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.reporting.projections.controller.LedgerSnapshotController}.
 * Requires Docker (Testcontainers Postgres).
 */
@DisplayName("LedgerSnapshotController — Integration Tests")
class LedgerSnapshotControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    private String adminToken;

    @BeforeEach
    void setUp() {
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), json()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();
    }

    // ── POST /balance-snapshots/run ──────────────────────────────────────────

    @Test
    @DisplayName("POST /balance-snapshots/run — 200 with list of snapshots (one per account)")
    void runSnapshot_returns200WithSnapshotList() {
        String date = LocalDate.now().toString();
        ResponseEntity<List<LedgerBalanceSnapshotDTO>> resp = rest.exchange(
                base() + "/api/v2/admin/ledger/balance-snapshots/run?date=" + date,
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The ledger is seeded with accounts on startup; expect at least one snapshot
        assertThat(resp.getBody()).isNotEmpty();
        resp.getBody().forEach(s -> {
            assertThat(s.getAccountId()).isNotNull();
            assertThat(s.getSnapshotDate()).isEqualTo(LocalDate.parse(date));
            assertThat(s.getBalance()).isNotNull();
        });
    }

    @Test
    @DisplayName("POST /balance-snapshots/run (no date param) — defaults to today")
    void runSnapshot_defaultsToToday() {
        ResponseEntity<List<LedgerBalanceSnapshotDTO>> resp = rest.exchange(
                base() + "/api/v2/admin/ledger/balance-snapshots/run",
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        // Each row should have today's date
        resp.getBody().forEach(s ->
                assertThat(s.getSnapshotDate()).isEqualTo(LocalDate.now()));
    }

    @Test
    @DisplayName("POST /balance-snapshots/run (same date twice) — idempotent, no duplicate rows")
    void runSnapshot_idempotent_sameDateTwice() {
        String date = LocalDate.now().minusDays(1).toString(); // use yesterday to avoid collision with other tests

        ResponseEntity<List<LedgerBalanceSnapshotDTO>> first = rest.exchange(
                base() + "/api/v2/admin/ledger/balance-snapshots/run?date=" + date,
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<>() {});
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        int countAfterFirst = first.getBody().size();

        ResponseEntity<List<LedgerBalanceSnapshotDTO>> second = rest.exchange(
                base() + "/api/v2/admin/ledger/balance-snapshots/run?date=" + date,
                HttpMethod.POST,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<>() {});
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        int countAfterSecond = second.getBody().size();

        // Idempotent: same number of accounts returned; no new rows
        assertThat(countAfterSecond).isEqualTo(countAfterFirst);
    }

    // ── GET /balance-snapshots ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /balance-snapshots — 200 (returns previously generated snapshots)")
    void getSnapshots_returnsCorrectSnapshots() {
        String date = LocalDate.now().minusDays(2).toString();

        // Generate a snapshot first
        rest.exchange(
                base() + "/api/v2/admin/ledger/balance-snapshots/run?date=" + date,
                HttpMethod.POST, new HttpEntity<>(auth()),
                new ParameterizedTypeReference<List<LedgerBalanceSnapshotDTO>>() {});

        // List snapshots with date filter
        ResponseEntity<List<LedgerBalanceSnapshotDTO>> resp = rest.exchange(
                base() + "/api/v2/admin/ledger/balance-snapshots?from=" + date + "&to=" + date,
                HttpMethod.GET,
                new HttpEntity<>(auth()),
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    @DisplayName("GET /balance-snapshots — 401 without auth")
    void getSnapshots_requiresAuth() {
        ResponseEntity<Map<String, Object>> resp = rest.exchange(
                base() + "/api/v2/admin/ledger/balance-snapshots",
                HttpMethod.GET,
                new HttpEntity<>(json()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
}
