package com.firstclub.membership.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Cache configuration.
 *
 * The default (Caffeine) cache manager is auto-configured from {@code spring.cache.caffeine.spec};
 * the {@code entitlements} cache there inherits that TTL. This customizer applies a dedicated,
 * configurable TTL (and JSON serialization) to the {@code entitlements} cache when the Redis cache
 * manager is active (the {@code redis} profile) — the production read path. It is inert under
 * Caffeine, so it is safe to define unconditionally.
 */
@Configuration
public class CacheConfig {

    @Bean
    RedisCacheManagerBuilderCustomizer entitlementsCacheCustomizer(
            @Value("${entitlements.cache.ttl-seconds:300}") long ttlSeconds) {
        RedisCacheConfiguration entitlements = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(ttlSeconds))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));
        return builder -> builder.withCacheConfiguration("entitlements", entitlements);
    }
}
