package com.firstclub.membership.entity;

import java.util.Optional;

/**
 * Product categories that benefits can target (e.g. an extra discount on BEAUTY).
 *
 * Cart line items carry a free-text category; {@link #from(String)} maps it leniently so an
 * unknown or missing category simply means "no category-specific benefit applies" rather than
 * an error — checkout must never fail on an unrecognised category.
 */
public enum ProductCategory {
    GROCERY,
    FRESH_PRODUCE,
    BEVERAGES,
    ELECTRONICS,
    BEAUTY;

    /** Lenient parse: case-insensitive, tolerates spaces/hyphens; unknown/blank → empty. */
    public static Optional<ProductCategory> from(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (ProductCategory c : values()) {
            if (c.name().equals(normalized)) return Optional.of(c);
        }
        return Optional.empty();
    }
}
