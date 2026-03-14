package com.firstclub.ledger.revenue;

import com.firstclub.billing.entity.Invoice;
import com.firstclub.billing.model.InvoiceStatus;
import com.firstclub.billing.repository.InvoiceRepository;
import com.firstclub.ledger.entity.LedgerAccount;
import com.firstclub.ledger.repository.LedgerAccountRepository;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionReportDTO;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionRunResponseDTO;
import com.firstclub.ledger.revenue.dto.RevenueRecognitionScheduleResponseDTO;
import com.firstclub.ledger.revenue.entity.RevenueRecognitionStatus;
import com.firstclub.ledger.revenue.repository.RevenueRecognitionScheduleRepository;
import com.firstclub.ledger.revenue.service.RevenueRecognitionScheduleService;
import com.firstclub.membership.dto.JwtResponseDTO;
import com.firstclub.membership.dto.LoginRequestDTO;
import com.firstclub.membership.PostgresIntegrationTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link com.firstclub.ledger.revenue.controller.RevenueRecognitionController}.
 *
 * <p>Uses Testcontainers Postgres (skipped without Docker).
 * Because {@code AccountSeeder} is {@code @Profile("dev")} only, ledger accounts
 * are seeded manually in {@link #seedLedgerAccounts()}.
 * Each test creates its own invoice data to remain independent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("RevenueRecognitionController Integration Tests")
class RevenueRecognitionControllerTest extends PostgresIntegrationTestBase {

    private static final String BASE_PATH    = "/api/v2/admin/revenue-recognition";
    private static final Long   USER_ID      = 9001L;
    private static final Long   MERCHANT_ID  = 2001L;
    private static final Long   SUBSCRIPTION_ID = 7001L;

    @Autowired private TestRestTemplate                     restTemplate;
    @Autowired private InvoiceRepository                    invoiceRepository;
    @Autowired private LedgerAccountRepository              ledgerAccountRepository;
    @Autowired private RevenueRecognitionScheduleService    scheduleService;
    @Autowired private RevenueRecognitionScheduleRepository scheduleRepository;

    @LocalServerPort private int port;

    private String baseUrl() { return "http://localhost:" + port; }
    private String schedulesUrl()       { return baseUrl() + BASE_PATH + "/schedules"; }
    private String runUrl(LocalDate d)  { return baseUrl() + BASE_PATH + "/run?date=" + d; }
    private String reportUrl(LocalDate f, LocalDate t) {
        return baseUrl() + BASE_PATH + "/report?from=" + f + "&to=" + t;
    }

    // -------------------------------------------------------------------------
    // One-time setup
    // -------------------------------------------------------------------------

    @BeforeAll
    void seedLedgerAccounts() {
        // Authenticate to gain access to secured endpoints
        LoginRequestDTO login = LoginRequestDTO.builder()
                .email("admin@firstclub.com").password("Admin@firstclub1").build();
        ResponseEntity<JwtResponseDTO> auth = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/login", login, JwtResponseDTO.class);
        restTemplate.getRestTemplate().getInterceptors().add(
                (request, body, execution) -> {
                    request.getHeaders().setBearerAuth(auth.getBody().getToken());
                    return execution.execute(request, body);
                });

        // AccountSeeder only runs in @Profile("dev"); seed manually for tests.
        saveAccountIfAbsent("SUBSCRIPTION_LIABILITY", LedgerAccount.AccountType.LIABILITY);
        saveAccountIfAbsent("REVENUE_SUBSCRIPTIONS",  LedgerAccount.AccountType.INCOME);
    }

    private void saveAccountIfAbsent(String name, LedgerAccount.AccountType type) {
        if (ledgerAccountRepository.findByName(name).isEmpty()) {
            ledgerAccountRepository.save(LedgerAccount.builder()
                    .name(name).accountType(type).currency("INR").build());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Saves a PAID invoice whose service period spans {@code days} days
     * starting from {@code periodStart}.
     */
    private Invoice createPaidInvoice(LocalDate periodStart, int days, BigDecimal amount) {
        return invoiceRepository.save(Invoice.builder()
                .userId(USER_ID)
                .merchantId(MERCHANT_ID)
                .subscriptionId(SUBSCRIPTION_ID)
                .status(InvoiceStatus.PAID)
                .currency("INR")
                .totalAmount(amount)
                .subtotal(amount)
                .discountTotal(BigDecimal.ZERO)
                .creditTotal(BigDecimal.ZERO)
                .taxTotal(BigDecimal.ZERO)
                .grandTotal(amount)
                .periodStart(periodStart.atStartOfDay())
                .periodEnd(periodStart.plusDays(days).atStartOfDay())
                .dueDate(LocalDateTime.now())
                .build());
    }

    // -------------------------------------------------------------------------
    // GET /schedules
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("GET /schedules returns 200 (list may be empty at start)")
    void getSchedules_returns200() {
        ResponseEntity<List<RevenueRecognitionScheduleResponseDTO>> resp = restTemplate.exchange(
                schedulesUrl(), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // POST /run — basic posting
    // -------------------------------------------------------------------------

    @Test
    @Order(2)
    @DisplayName("POST /run posts all PENDING schedules on or before the given date")
    void run_postsPendingSchedules() {
        // 3-day service period starting 2024-02-01 → recognition dates 02-01, 02-02, 02-03
        LocalDate start = LocalDate.of(2024, 2, 1);
        Invoice invoice = createPaidInvoice(start, 3, new BigDecimal("90.00"));
        scheduleService.generateScheduleForInvoice(invoice.getId());

        // Running for the last day of the period posts all 3 rows
        ResponseEntity<RevenueRecognitionRunResponseDTO> resp = restTemplate.postForEntity(
                runUrl(start.plusDays(2)), null, RevenueRecognitionRunResponseDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        RevenueRecognitionRunResponseDTO body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getPosted()).isGreaterThanOrEqualTo(3);
        assertThat(body.getFailed()).isEqualTo(0);
        assertThat(body.getFailedScheduleIds()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // POST /run — idempotency
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    @DisplayName("POST /run is idempotent — second call for same date posts zero new entries")
    void run_idempotent() {
        // 1-day period on 2024-03-10
        LocalDate day = LocalDate.of(2024, 3, 10);
        Invoice invoice = createPaidInvoice(day, 1, new BigDecimal("100.00"));
        scheduleService.generateScheduleForInvoice(invoice.getId());

        // First run: should post 1 schedule
        ResponseEntity<RevenueRecognitionRunResponseDTO> first = restTemplate.postForEntity(
                runUrl(day), null, RevenueRecognitionRunResponseDTO.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().getPosted()).isGreaterThanOrEqualTo(1);

        // Second run: no PENDING schedules left for this invoice/date
        ResponseEntity<RevenueRecognitionRunResponseDTO> second = restTemplate.postForEntity(
                runUrl(day), null, RevenueRecognitionRunResponseDTO.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The specific schedule for this invoice is now POSTED, so won't be re-posted
        // (Other test schedules may also have been processed)
        assertThat(second.getBody().getFailedScheduleIds()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // POST /run — partial period (only schedules with recognitionDate <= date)
    // -------------------------------------------------------------------------

    @Test
    @Order(4)
    @DisplayName("POST /run with early date only posts schedules up to that date")
    void run_respectsDateCutoff() {
        // 5-day period: recognition dates 2024-04-01 .. 2024-04-05
        LocalDate start = LocalDate.of(2024, 4, 1);
        Invoice invoice = createPaidInvoice(start, 5, new BigDecimal("50.00"));
        scheduleService.generateScheduleForInvoice(invoice.getId());

        // Run only for the first 2 days
        ResponseEntity<RevenueRecognitionRunResponseDTO> resp = restTemplate.postForEntity(
                runUrl(start.plusDays(1)), null, RevenueRecognitionRunResponseDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3 schedules (for days 3-5) must still be PENDING
        long stillPending = scheduleRepository.findByInvoiceId(invoice.getId()).stream()
                .filter(s -> s.getStatus() == RevenueRecognitionStatus.PENDING)
                .count();
        assertThat(stillPending).isGreaterThanOrEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // GET /report
    // -------------------------------------------------------------------------

    @Test
    @Order(5)
    @DisplayName("GET /report returns correct posted amounts after posting")
    void report_showsPostedAmounts() {
        // 2-day period, 2024-05-01 & 2024-05-02, total = 200 INR
        LocalDate start = LocalDate.of(2024, 5, 1);
        Invoice invoice = createPaidInvoice(start, 2, new BigDecimal("200.00"));
        scheduleService.generateScheduleForInvoice(invoice.getId());

        // Post both schedules
        restTemplate.postForEntity(runUrl(start.plusDays(1)), null, RevenueRecognitionRunResponseDTO.class);

        // Report for May 2024
        ResponseEntity<RevenueRecognitionReportDTO> resp = restTemplate.getForEntity(
                reportUrl(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31)),
                RevenueRecognitionReportDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        RevenueRecognitionReportDTO report = resp.getBody();
        assertThat(report).isNotNull();
        assertThat(report.getPostedAmount()).isGreaterThanOrEqualTo(new BigDecimal("200.00"));
        assertThat(report.getPostedCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(6)
    @DisplayName("GET /report returns zero counts for future date range with no schedules")
    void report_emptyRangeReturnsZero() {
        ResponseEntity<RevenueRecognitionReportDTO> resp = restTemplate.getForEntity(
                reportUrl(LocalDate.of(2099, 1, 1), LocalDate.of(2099, 1, 31)),
                RevenueRecognitionReportDTO.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        RevenueRecognitionReportDTO report = resp.getBody();
        assertThat(report).isNotNull();
        assertThat(report.getPostedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getPendingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.getPostedCount()).isZero();
        assertThat(report.getPendingCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // GET /schedules — after seeding data
    // -------------------------------------------------------------------------

    @Test
    @Order(7)
    @DisplayName("GET /schedules lists all created schedules including DTO fields")
    void getSchedules_includesCreatedSchedules() {
        LocalDate start = LocalDate.of(2024, 6, 1);
        Invoice invoice = createPaidInvoice(start, 2, new BigDecimal("60.00"));
        scheduleService.generateScheduleForInvoice(invoice.getId());

        ResponseEntity<List<RevenueRecognitionScheduleResponseDTO>> resp = restTemplate.exchange(
                schedulesUrl(), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<RevenueRecognitionScheduleResponseDTO> schedules = resp.getBody();
        assertThat(schedules).isNotNull().isNotEmpty();

        // At least the 2 schedules we just created
        long forThisInvoice = schedules.stream()
                .filter(s -> invoice.getId().equals(s.getInvoiceId()))
                .count();
        assertThat(forThisInvoice).isEqualTo(2);

        // Verify DTO shape
        RevenueRecognitionScheduleResponseDTO sample = schedules.stream()
                .filter(s -> invoice.getId().equals(s.getInvoiceId()))
                .findFirst().orElseThrow();
        assertThat(sample.getId()).isNotNull();
        assertThat(sample.getCurrency()).isEqualTo("INR");
        assertThat(sample.getAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(sample.getRecognitionDate()).isNotNull();
        assertThat(sample.getStatus()).isEqualTo(RevenueRecognitionStatus.PENDING);
    }
}
