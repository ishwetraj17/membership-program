package com.firstclub.audit.aspect;

import com.firstclub.audit.service.AuditEntryService;
import com.firstclub.platform.context.RequestContext;
import com.firstclub.platform.context.RequestContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Parameter;

/**
 * Spring AOP aspect that intercepts methods annotated with
 * {@link FinancialOperation} and produces a compliance-grade audit entry in
 * the {@code audit_entries} table, regardless of whether the operation
 * succeeds or throws.
 *
 * <h2>Audit flow</h2>
 * <pre>
 *   → method invoked
 *       → capture request context (requestId, correlationId, merchantId, actorId, apiVersion)
 *       → proceed()
 *           → ON SUCCESS: record(success=true)
 *           → ON THROW:   record(success=false, failureReason=e.getMessage()) then rethrow
 * </pre>
 *
 * <h2>Entity ID extraction</h2>
 * {@link FinancialOperation#entityIdExpression()} is evaluated as a SpEL
 * expression against a context that exposes:
 * <ul>
 *   <li>{@code #args[n]} — positional argument at index {@code n}</li>
 *   <li>Named parameters ({@code #id}, {@code #request}, …) when the class
 *       retains parameter name metadata (standard with Spring Boot)</li>
 *   <li>{@code #result} — the return value (set only on the success path)</li>
 * </ul>
 *
 * <h2>Transaction note</h2>
 * {@link AuditEntryService#record} uses {@code REQUIRES_NEW} propagation so
 * the audit entry commits even when the caller's transaction rolls back.
 *
 * @see FinancialOperation
 * @see AuditEntryService
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class FinancialAuditAspect {

    private final AuditEntryService auditEntryService;

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    /**
     * Around advice bound to every method carrying {@link FinancialOperation}.
     *
     * @param pjp              the intercepted join point
     * @param financialOperation the annotation instance with operation metadata
     * @return whatever the intercepted method returns
     * @throws Throwable rethrown after the failure audit record is committed
     */
    @Around("@annotation(financialOperation)")
    public Object auditFinancialOperation(
            ProceedingJoinPoint pjp,
            FinancialOperation  financialOperation
    ) throws Throwable {

        // Snapshot request context before proceeding (it is available now; may
        // not be if the operation is async later).
        String requestId     = null;
        String correlationId = null;
        Long   merchantId    = null;
        String actorId       = null;
        String apiVersion    = null;

        var ctxOpt = RequestContextHolder.current();
        if (ctxOpt.isPresent()) {
            RequestContext ctx = ctxOpt.get();
            requestId     = ctx.getRequestId();
            correlationId = ctx.getCorrelationId();
            merchantId    = ctx.getMerchantId();
            actorId       = ctx.getActorId();
            apiVersion    = ctx.getApiVersion();
        }

        String  operationType = financialOperation.operationType();
        String  entityType    = financialOperation.entityType();
        String  idExpr        = financialOperation.entityIdExpression();
        Object[] methodArgs   = pjp.getArgs();

        try {
            Object result = pjp.proceed();

            // Extract entity ID from return value or arguments (success path).
            Long entityId = extractEntityId(idExpr, methodArgs,
                    (MethodSignature) pjp.getSignature(), result);

            auditEntryService.record(
                    operationType,
                    operationType,         // action mirrors operationType
                    entityType,
                    entityId,
                    actorId,               // performedBy = actorId from request
                    true,
                    null,
                    requestId,
                    correlationId,
                    merchantId,
                    actorId,
                    apiVersion,
                    null                   // IP address not available from AOP layer
            );

            return result;

        } catch (Throwable t) {
            // Extract entity ID from arguments only (no return value on failure).
            Long entityId = extractEntityId(idExpr, methodArgs,
                    (MethodSignature) pjp.getSignature(), null);

            auditEntryService.record(
                    operationType,
                    operationType,
                    entityType,
                    entityId,
                    actorId,
                    false,
                    t.getMessage(),
                    requestId,
                    correlationId,
                    merchantId,
                    actorId,
                    apiVersion,
                    null
            );

            throw t; // never swallow — callers must see the exception
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Long extractEntityId(
            String idExpr,
            Object[] args,
            MethodSignature signature,
            Object result
    ) {
        if (idExpr == null || idExpr.isBlank()) {
            return null;
        }
        try {
            StandardEvaluationContext spelCtx = new StandardEvaluationContext();
            spelCtx.setVariable("args",   args);
            spelCtx.setVariable("result", result);

            Parameter[] params = signature.getMethod().getParameters();
            for (int i = 0; i < params.length; i++) {
                spelCtx.setVariable(params[i].getName(), args[i]);
            }

            Expression expr    = PARSER.parseExpression(idExpr);
            Object     idValue = expr.getValue(spelCtx);

            if (idValue instanceof Long l)   return l;
            if (idValue instanceof Number n) return n.longValue();
            if (idValue instanceof String s && !s.isBlank()) {
                try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            log.warn("FinancialAuditAspect: could not evaluate entityIdExpression '{}': {}",
                    idExpr, e.getMessage());
        }
        return null;
    }
}
