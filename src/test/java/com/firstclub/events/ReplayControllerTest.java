package com.firstclub.events;

import com.firstclub.events.dto.ReplayReportDTO;
import com.firstclub.events.dto.ReplayRequestDTO;
import com.firstclub.events.dto.ReplayResponseDTO;
import com.firstclub.membership.PostgresIntegrationTestBase;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link com.firstclub.events.controller.ReplayController}.
 *
 * <p>Covers both the V1 legacy endpoint and the new V2 validate / rebuild-projection
 * endpoints. Uses Testcontainers Postgres (skipped without Docker).
 */
@DisplayName("ReplayController — Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplayControllerTest extends PostgresIntegrationTestBase {

    private static final String ADMIN_EMAIL    = "admin@firstclub.com";
    private static final String ADMIN_PASSWORD = "Admin@firstclub1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;

    private String adminToken;

    private static final LocalDateTime FROM = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
    private static final LocalDateTime TO   = LocalDateTime.of(2024, 12, 31, 23, 59, 59);

    @BeforeAll
    void setUp() {
        ResponseEntity<JwtResponseDTO> auth = rest.postForEntity(
                base() + "/api/v1/auth/login",
                new HttpEntity<>(LoginRequestDTO.builder()
                        .email(ADMIN_EMAIL).password(ADMIN_PASSWORD).build(), jsonHeaders()),
                JwtResponseDTO.class);
        assertThat(auth.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminToken = auth.getBody().getToken();
    }

    // ── V1 legacy endpoint ────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /api/v1/admin/replay — returns 200 with ReplayReportDTO")
    void v1Replay_returns200() {
        String url = base() + "/api/v1/admin/replay"
                + "?from=" + iso(FROM) + "&to=" + iso(TO) + "&mode=VALIDATE_ONLY";

        ResponseEntity<ReplayReportDTO> resp = rest.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                ReplayReportDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReplayReportDTO body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMode()).isEqualTo("VALIDATE_ONLY");
        assertThat(body.getEventsScanned()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/v1/admin/replay — non-VALIDATE mode → 400 or 500")
    void v1Replay_nonValidateMode_returnsError() {
        String url = base() + "/api/v1/admin/replay"
                + "?from=" + iso(FROM) + "&to=" + iso(TO) + "&mode=REBUILD_PROJECTION";

        ResponseEntity<Object> resp = rest.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                Object.class);

        // GlobalExceptionHandler maps IllegalArgumentException to 400
        assertThat(resp.getStatusCode().value()).isIn(400, 500);
    }

    // ── V2 /validate endpoint ─────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("POST /api/v2/admin/replay/validate — returns 200 with ReplayResponseDTO")
    void v2Validate_returns200() {
        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO).build();

        ResponseEntity<ReplayResponseDTO> resp = rest.exchange(
                base() + "/api/v2/admin/replay/validate",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                ReplayResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ReplayResponseDTO body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMode()).isEqualTo("VALIDATE_ONLY");
        assertThat(body.getCountByType()).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("POST /api/v2/admin/replay/validate — forces mode=VALIDATE_ONLY even if request says otherwise")
    void v2Validate_forcesValidateOnlyMode() {
        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO).mode("REBUILD_PROJECTION").build(); // will be overridden

        ResponseEntity<ReplayResponseDTO> resp = rest.exchange(
                base() + "/api/v2/admin/replay/validate",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                ReplayResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getMode()).isEqualTo("VALIDATE_ONLY");
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/v2/admin/replay/validate — missing from/to → 400")
    void v2Validate_missingFromTo_returns400() {
        // Empty body — @NotNull on from/to should trigger validation
        ResponseEntity<Object> resp = rest.exchange(
                base() + "/api/v2/admin/replay/validate",
                HttpMethod.POST,
                new HttpEntity<>(new ReplayRequestDTO(), authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── V2 /rebuild-projection endpoint ──────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("POST /api/v2/admin/replay/rebuild-projection — supported name → 200")
    void v2RebuildProjection_supportedName_returns200() {
        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO)
                .projectionName("subscription_summary")
                .build();

        ResponseEntity<ReplayResponseDTO> resp = rest.exchange(
                base() + "/api/v2/admin/replay/rebuild-projection",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                ReplayResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getProjectionRebuilt()).isEqualTo("subscription_summary");
    }

    @Test
    @Order(7)
    @DisplayName("POST /api/v2/admin/replay/rebuild-projection — unsupported name → 400")
    void v2RebuildProjection_unsupportedName_returns400() {
        ReplayRequestDTO req = ReplayRequestDTO.builder()
                .from(FROM).to(TO)
                .projectionName("nonexistent_projection")
                .build();

        ResponseEntity<Object> resp = rest.exchange(
                base() + "/api/v2/admin/replay/rebuild-projection",
                HttpMethod.POST,
                new HttpEntity<>(req, authHeaders()),
                Object.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @Order(8)
    @DisplayName("POST /api/v2/admin/replay/validate — no auth → 401 or 403")
    void v2Validate_noToken_returns401or403() {
        ReplayRequestDTO req = ReplayRequestDTO.builder().from(FROM).to(TO).build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Object> resp = rest.exchange(
                base() + "/api/v2/admin/replay/validate",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                Object.class);

        assertThat(resp.getStatusCode().value()).isIn(401, 403);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String base() { return "http://localhost:" + port; }

    private String iso(LocalDateTime dt) {
        return dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

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
}
