package com.firstclub.platform.integrity;

import org.springframework.lang.Nullable;

/**
 * Contract for a single domain invariant checker.
 *
 * <p>Implementations are Spring {@code @Component}s registered with the
 * {@link IntegrityCheckRegistry}.  Each checker is stateless and safe to call
 * concurrently from multiple threads.
 *
 * <p><strong>Merchant scoping:</strong> if {@code merchantId} is non-null, the
 * check SHOULD restrict its query to that merchant's data.  If the checker
 * does not support merchant scoping, it MUST ignore the parameter and document
 * that behaviour.
 */
public interface IntegrityChecker {

    /**
     * Stable, dot-separated key that uniquely identifies this invariant across
     * releases.  Convention: {@code "{domain}.{rule_name}"}, e.g.
     * {@code "billing.invoice_total_equals_line_sum"}.
     */
    String getInvariantKey();

    /** Severity classification when a violation is found. */
    IntegrityCheckSeverity getSeverity();

    /**
     * Run the invariant check.
     *
     * @param merchantId optional merchant scope; {@code null} means platform-wide
     * @return the check result — never {@code null}
     */
    IntegrityCheckResult run(@Nullable Long merchantId);
}
