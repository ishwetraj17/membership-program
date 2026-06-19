package com.firstclub.membership.security;

import com.firstclub.membership.dto.LoginRequest;
import com.firstclub.membership.dto.LoginResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Part 1 — the Prometheus scrape endpoint is exposed, secured (operator-only), and
 * carries the application's custom and JVM meters in the OpenMetrics text format.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:obsdb;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "logging.level.com.firstclub.membership=WARN",
    "rate-limit.capacity=100000"
})
@DisplayName("Observability — Prometheus endpoint exposure & security")
class ObservabilityEndpointTest {

    @Autowired private TestRestTemplate restTemplate;
    @LocalServerPort private int port;

    private String url(String path) { return "http://localhost:" + port + path; }

    @Test @DisplayName("/actuator/health is public")
    void healthIsPublic() {
        ResponseEntity<String> resp = restTemplate.getForEntity(url("/actuator/health"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test @DisplayName("/actuator/prometheus requires authentication")
    void prometheusRequiresAuth() {
        ResponseEntity<String> resp = restTemplate.getForEntity(url("/actuator/prometheus"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test @DisplayName("/actuator/prometheus is forbidden for non-admin users")
    void prometheusForbiddenForUser() {
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/actuator/prometheus"), org.springframework.http.HttpMethod.GET,
                bearer(login("demo", "demo123")), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test @DisplayName("admin scrape returns OpenMetrics with app + JVM meters and the application tag")
    void adminScrapeExposesMeters() {
        // A successful admin login also exercises the membership.auth.login counter.
        String adminToken = login("admin", "admin123");
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/actuator/prometheus"), org.springframework.http.HttpMethod.GET,
                bearer(adminToken), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = resp.getBody();
        assertThat(body).isNotNull();
        // JVM meters are auto-registered; the common tag stamps every series.
        assertThat(body).contains("jvm_memory_used_bytes");
        assertThat(body).contains("application=\"FirstClub Membership Program\"");
        // Domain meter emitted by the auth flow above.
        assertThat(body).contains("membership_auth_login_total");
    }

    private String login(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<LoginResponse> resp = restTemplate.postForEntity(
                url("/api/v1/auth/login"),
                new HttpEntity<>(new LoginRequest(username, password), headers), LoginResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody().getToken();
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
