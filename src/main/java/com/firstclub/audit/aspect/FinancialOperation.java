package com.firstclub.audit.aspect;

import java.lang.annotation.*;

/**
 * Marks a service method as a <em>financial operation</em> whose execution
 * must produce a compliance-grade audit entry regardless of outcome.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @FinancialOperation(
 *     operationType  = "SUBSCRIPTION_CREATE",
 *     entityType     = "Subscription",
 *     entityIdExpression = "#result?.id"   // SpEL evaluated after the method returns
 * )
 * public Subscription createSubscription(SubscriptionRequest req) { ... }
 * }</pre>
 *
 * <h2>SpEL context for {@code entityIdExpression}</h2>
 * The expression is evaluated against an {@code EvaluationContext} that exposes:
 * <ul>
 *   <li>{@code #args[n]} — method argument at position {@code n}</li>
 *   <li>Named parameter variables ({@code #id}, {@code #request}, …) if the
 *       class file retains parameter names (standard with Spring Boot)</li>
 *   <li>{@code #result} — the value returned by the method (available only
 *       when the method succeeds; {@code null} on failure)</li>
 * </ul>
 *
 * @see com.firstclub.audit.aspect.FinancialAuditAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FinancialOperation {

    /**
     * Machine-readable constant identifying the financial mutation, e.g.
     * {@code SUBSCRIPTION_CREATE}, {@code PAYMENT_CONFIRM}, {@code REFUND_ISSUE}.
     */
    String operationType();

    /**
     * Domain entity type being mutated, e.g. {@code "Subscription"},
     * {@code "PaymentIntent"}, {@code "MembershipPlan"}.
     */
    String entityType();

    /**
     * Optional SpEL expression to extract the entity primary key.
     * Evaluated post-invocation; may reference {@code #result}.
     * Leave empty when the entity ID is not available or not relevant.
     */
    String entityIdExpression() default "";
}
