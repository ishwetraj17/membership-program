package com.firstclub.platform.idempotency.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as idempotent.
 *
 * <p>When applied, the {@code IdempotencyFilter} enforces the following
 * contract for requests to the annotated endpoint:
 * <ol>
 *   <li>A valid {@code Idempotency-Key} header (max 80 chars) is required.
 *       Missing or blank header returns HTTP 400.</li>
 *   <li>If the key was already processed with the same request hash,
 *       the original response is replayed without executing the handler.</li>
 *   <li>If the key was already used with a <em>different</em> request body,
 *       HTTP 409 is returned.</li>
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * How many hours the idempotency record is retained after creation.
     * After this TTL the record is deleted by the nightly cleanup job
     * and the key may be safely reused.
     */
    int ttlHours() default 24;
}
