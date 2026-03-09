package com.firstclub.platform.logging;

import org.slf4j.MDC;

import java.util.Arrays;
import java.util.Collection;

/**
 * Safe, null-tolerant utilities for working with SLF4J MDC.
 *
 * <h3>Why not call MDC directly?</h3>
 * <ul>
 *   <li>Direct {@code MDC.put(key, null)} throws {@link IllegalArgumentException}
 *       in some SLF4J implementations — this utility guards against null values.</li>
 *   <li>Centralises the "remove on null" pattern that every caller otherwise
 *       has to implement inline.</li>
 *   <li>Provides {@link #set(String, Long)} and {@link #set(String, Object)} overloads
 *       so callers never need to convert to String manually.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * MDC is backed by a {@code ThreadLocal} — all operations are already
 * per-thread and require no additional synchronisation.
 *
 * <h3>Usage pattern</h3>
 * <pre>{@code
 *   MdcUtil.set(StructuredLogFields.MERCHANT_ID, merchantId);
 *   try {
 *       doWork();
 *   } finally {
 *       MdcUtil.remove(StructuredLogFields.MERCHANT_ID);
 *   }
 * }</pre>
 */
public final class MdcUtil {

    private MdcUtil() { /* static utility */ }

    /**
     * Sets {@code key → value} in MDC.
     * If {@code value} is null or blank the key is silently removed instead
     * of storing an empty entry (which pollutes log output).
     */
    public static void set(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }

    /**
     * Sets {@code key → value.toString()} in MDC.
     * If {@code value} is null the key is silently removed.
     */
    public static void set(String key, Object value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value.toString());
        }
    }

    /**
     * Removes {@code key} from MDC.  No-op if the key is absent.
     */
    public static void remove(String key) {
        MDC.remove(key);
    }

    /**
     * Removes all of the given keys from MDC in one call.
     * Convenient for a filter's {@code finally} cleanup block.
     */
    public static void removeAll(String... keys) {
        for (String key : keys) {
            MDC.remove(key);
        }
    }

    /**
     * Removes all keys in the given collection from MDC.
     */
    public static void removeAll(Collection<String> keys) {
        keys.forEach(MDC::remove);
    }

    /**
     * Sets a Long value — null-safe; stores {@code value.toString()} when non-null,
     * removes the key when null.
     */
    public static void set(String key, Long value) {
        set(key, value != null ? value.toString() : null);
    }

    /**
     * Convenience: remove multiple keys and a varargs extension at the same time.
     * Primarily used by filters that set a fixed set of keys at entry.
     *
     * <pre>{@code
     *   MdcUtil.clear(StructuredLogFields.REQUEST_ID,
     *                  StructuredLogFields.CORRELATION_ID);
     * }</pre>
     *
     * @param first    first key to remove (required to distinguish from varargs overload)
     * @param rest     additional keys to remove
     */
    public static void clear(String first, String... rest) {
        MDC.remove(first);
        for (String key : rest) {
            MDC.remove(key);
        }
    }
}
