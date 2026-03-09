package com.firstclub.catalog.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Domain exception for catalog-related errors (products, prices, price versions).
 *
 * Follows the same pattern as {@link com.firstclub.merchant.exception.MerchantException}
 * and {@link com.firstclub.customer.exception.CustomerException}.
 */
@Getter
public class CatalogException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public CatalogException(String message, String errorCode, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    // ── Product factory methods ──────────────────────────────────────────────

    public static CatalogException productNotFound(Long merchantId, Long productId) {
        return new CatalogException(
                "Product " + productId + " not found in merchant " + merchantId,
                "PRODUCT_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static CatalogException duplicateProductCode(Long merchantId, String code) {
        return new CatalogException(
                "Product code '" + code + "' already exists for merchant " + merchantId,
                "DUPLICATE_PRODUCT_CODE",
                HttpStatus.CONFLICT
        );
    }

    public static CatalogException productArchived(Long productId) {
        return new CatalogException(
                "Product " + productId + " is ARCHIVED and cannot be used for new subscriptions",
                "PRODUCT_ARCHIVED",
                HttpStatus.BAD_REQUEST
        );
    }

    // ── Price factory methods ────────────────────────────────────────────────

    public static CatalogException priceNotFound(Long merchantId, Long priceId) {
        return new CatalogException(
                "Price " + priceId + " not found in merchant " + merchantId,
                "PRICE_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static CatalogException duplicatePriceCode(Long merchantId, String code) {
        return new CatalogException(
                "Price code '" + code + "' already exists for merchant " + merchantId,
                "DUPLICATE_PRICE_CODE",
                HttpStatus.CONFLICT
        );
    }

    public static CatalogException priceInactive(Long priceId) {
        return new CatalogException(
                "Price " + priceId + " is inactive and cannot be used for new subscriptions",
                "PRICE_INACTIVE",
                HttpStatus.BAD_REQUEST
        );
    }

    public static CatalogException invalidBillingInterval() {
        return new CatalogException(
                "RECURRING prices require billingIntervalUnit and billingIntervalCount >= 1",
                "INVALID_BILLING_INTERVAL",
                HttpStatus.BAD_REQUEST
        );
    }

    // ── PriceVersion factory methods ─────────────────────────────────────────

    public static CatalogException priceVersionNotFound(Long versionId) {
        return new CatalogException(
                "PriceVersion " + versionId + " not found",
                "PRICE_VERSION_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }

    public static CatalogException overlappingPriceVersion(Long priceId) {
        return new CatalogException(
                "A price version for price " + priceId + " already exists that overlaps the requested effective window",
                "OVERLAPPING_PRICE_VERSION",
                HttpStatus.CONFLICT
        );
    }

    public static CatalogException effectiveFromInPast() {
        return new CatalogException(
                "effectiveFrom must be in the future or at the current time (past dates not allowed for new versions)",
                "EFFECTIVE_FROM_IN_PAST",
                HttpStatus.BAD_REQUEST
        );
    }
}
