package com.firstclub.membership.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firstclub.platform.idempotency.IdempotencyFilter;
import com.firstclub.platform.idempotency.IdempotencyService;
import com.firstclub.platform.idempotency.RedisIdempotencyStore;
import com.firstclub.platform.idempotency.service.IdempotencyConflictDetector;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link IdempotencyFilter} as a servlet filter for all requests.
 *
 * <p>In {@code @WebMvcTest} slices that need to verify idempotency behaviour,
 * add {@code @MockitoBean IdempotencyService} to provide the dependency.
 * In all other {@code @WebMvcTest} slices, add {@code @MockitoBean IdempotencyService}
 * as a stub so that this configuration class can satisfy its wiring.
 */
@Configuration
public class IdempotencyFilterConfig {

    /**
     * Creates the {@link IdempotencyFilter} Spring bean. Spring performs
     * field injection (e.g.&nbsp;{@code @Lazy RequestMappingHandlerMapping})
     * after construction.
     */
    @Bean
    public IdempotencyFilter idempotencyFilter(IdempotencyService idempotencyService,
                                               RedisIdempotencyStore redisIdempotencyStore,
                                               ObjectMapper objectMapper,
                                               IdempotencyConflictDetector conflictDetector) {
        return new IdempotencyFilter(idempotencyService, redisIdempotencyStore,
                objectMapper, conflictDetector);
    }

    /**
     * Wraps the filter in a {@link FilterRegistrationBean} to apply it at low
     * precedence (after Spring Security), giving explicit control over ordering.
     */
    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilterRegistration(
            IdempotencyFilter idempotencyFilter) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(idempotencyFilter);
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }
}

