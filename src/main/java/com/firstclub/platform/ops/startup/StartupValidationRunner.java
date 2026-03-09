package com.firstclub.platform.ops.startup;

import com.firstclub.ledger.repository.LedgerAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Advisory startup checks that log warnings when the deployment looks
 * misconfigured.  Checks are non-fatal: they log at ERROR/WARN level so
 * alerting pipelines can catch them, but they do not prevent startup
 * (which would break CI environments and local dev).
 *
 * <h3>Checks performed</h3>
 * <ol>
 *   <li>Webhook secret is not the default dev placeholder.</li>
 *   <li>Chart of accounts has been seeded (at least one LedgerAccount exists).</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupValidationRunner implements ApplicationRunner {

    private static final String DEFAULT_WEBHOOK_SECRET = "dev-only-webhook-secret-change-in-prod";

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Value("${payments.webhook.secret:}")
    private String webhookSecret;

    private final LedgerAccountRepository ledgerAccountRepository;

    @Override
    public void run(ApplicationArguments args) {
        validateWebhookSecret();
        validateChartOfAccounts();
    }

    private void validateWebhookSecret() {
        boolean isDefault = DEFAULT_WEBHOOK_SECRET.equals(webhookSecret);
        if (isDefault) {
            boolean isProd = !activeProfile.contains("dev") && !activeProfile.contains("test");
            if (isProd) {
                log.error("[STARTUP] SECURITY RISK: payments.webhook.secret is the default dev value. " +
                          "Replace it before serving real traffic.");
            } else {
                log.warn("[STARTUP] payments.webhook.secret is the default dev value — change before production.");
            }
        }
    }

    private void validateChartOfAccounts() {
        long accountCount = ledgerAccountRepository.count();
        if (accountCount == 0) {
            log.warn("[STARTUP] No LedgerAccounts found. Run the AccountSeeder or apply the V8 migration " +
                     "with the default chart of accounts before going live.");
        } else {
            log.info("[STARTUP] Chart of accounts present ({} accounts).", accountCount);
        }
    }
}
