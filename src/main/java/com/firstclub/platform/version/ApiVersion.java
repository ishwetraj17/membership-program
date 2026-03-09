package com.firstclub.platform.version;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable API version identifier using a date-based {@code YYYY-MM-DD} scheme.
 *
 * <h3>Why date-based versioning?</h3>
 * <ul>
 *   <li>Versions are self-documenting — you know exactly when a breaking
 *       change was introduced.</li>
 *   <li>Lexicographic comparison equals chronological order, so
 *       {@link #isBefore}/{@link #isAfterOrEqual} need no additional logic.</li>
 *   <li>Common in production API platforms (Stripe, Recurly, Paddle).</li>
 * </ul>
 *
 * <h3>Usage in version-gated code</h3>
 * <pre>{@code
 *   ApiVersion requested = ApiVersionContext.currentOrDefault();
 *
 *   if (requested.isAfterOrEqual(ApiVersion.V_2025_01)) {
 *       // Use new enriched response format
 *   } else {
 *       // Return legacy compact format
 *   }
 * }</pre>
 *
 * <h3>Adding a new version</h3>
 * Add a new constant here, update {@link #CURRENT}, and implement the
 * version-gated branch in the affected controller/service.
 */
public final class ApiVersion implements Comparable<ApiVersion> {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$");

    // ── Version constants ────────────────────────────────────────────────────

    /** Baseline version shipped at platform launch. */
    public static final ApiVersion V_2024_01 = new ApiVersion("2024-01-01");

    /** Version that introduced hardened concurrency, idempotency, and ledger guarantees. */
    public static final ApiVersion V_2025_01 = new ApiVersion("2025-01-01");

    /**
     * Current production version.  Update this constant whenever a
     * breaking public contract change is released.
     */
    public static final ApiVersion CURRENT = V_2025_01;

    /**
     * Default version served to clients that do not send {@code X-API-Version}.
     * Pinned to the oldest supported version to ensure backward compatibility.
     */
    public static final ApiVersion DEFAULT = V_2024_01;

    // ── Instance ─────────────────────────────────────────────────────────────

    private final String value;

    private ApiVersion(String value) {
        this.value = value;
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Parse a version string in {@code YYYY-MM-DD} format.
     *
     * @param version non-null, non-blank version string
     * @throws IllegalArgumentException if the format is invalid
     */
    public static ApiVersion fromString(String version) {
        Objects.requireNonNull(version, "version must not be null");
        String trimmed = version.trim();
        if (!VERSION_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid ApiVersion format '" + version + "'. Expected YYYY-MM-DD.");
        }
        return new ApiVersion(trimmed);
    }

    /**
     * Parse a version string, returning {@link #DEFAULT} if the input is
     * null or blank.  Useful when reading the optional header value.
     */
    public static ApiVersion parseOrDefault(String version) {
        if (version == null || version.isBlank()) {
            return DEFAULT;
        }
        return fromString(version);
    }

    // ── Comparison predicates ────────────────────────────────────────────────

    /** Is this version strictly before {@code other}? */
    public boolean isBefore(ApiVersion other) {
        return compareTo(other) < 0;
    }

    /** Is this version equal to {@code other}? */
    public boolean isEqualTo(ApiVersion other) {
        return compareTo(other) == 0;
    }

    /** Is this version equal to or after {@code other}? */
    public boolean isAfterOrEqual(ApiVersion other) {
        return compareTo(other) >= 0;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    /** Raw version string, e.g. {@code "2025-01-01"}. */
    public String getValue() {
        return value;
    }

    // ── Comparable ───────────────────────────────────────────────────────────

    /**
     * Chronological comparison.  {@code YYYY-MM-DD} strings compare
     * lexicographically in the same order as chronologically.
     */
    @Override
    public int compareTo(ApiVersion other) {
        return this.value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiVersion v)) return false;
        return Objects.equals(value, v.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
