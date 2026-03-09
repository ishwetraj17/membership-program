package com.firstclub.reporting.ops.timeline.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.firstclub.platform.redis.RedisJsonCodec;
import com.firstclub.platform.redis.RedisKeyFactory;
import com.firstclub.reporting.ops.timeline.dto.TimelineEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Optional Redis hot-cache for ops timeline reads.
 *
 * <h3>Key pattern</h3>
 * <pre>{@code {env}:firstclub:timeline:{merchantId}:{entityType}:{entityId} }</pre>
 *
 * <h3>TTL</h3>
 * {@value #TTL_SECONDS} seconds (60 s).  The {@code ops_timeline_events} table
 * is always the authoritative source — callers must fall back to the DB on a
 * cache miss.
 *
 * <h3>Degradation</h3>
 * All methods are safe when Redis is unavailable: reads return
 * {@link Optional#empty()} and writes / evictions are silently ignored.
 */
@Slf4j
@Component
public class TimelineCacheService {

    public static final int TTL_SECONDS = 60;

    private static final TypeReference<List<TimelineEventDTO>> LIST_TYPE = new TypeReference<>() {};

    private final ObjectProvider<StringRedisTemplate> templateProvider;
    private final RedisKeyFactory                     keyFactory;
    private final RedisJsonCodec                      codec;

    public TimelineCacheService(ObjectProvider<StringRedisTemplate> templateProvider,
                                RedisKeyFactory keyFactory,
                                RedisJsonCodec codec) {
        this.templateProvider = templateProvider;
        this.keyFactory       = keyFactory;
        this.codec            = codec;
    }

    /**
     * Returns the cached timeline for the given entity, or {@link Optional#empty()}
     * on a cache miss or when Redis is unavailable.
     */
    public Optional<List<TimelineEventDTO>> get(Long merchantId, String entityType, Long entityId) {
        StringRedisTemplate t = template();
        if (t == null) return Optional.empty();
        try {
            String key  = keyFactory.opsTimelineKey(str(merchantId), entityType, str(entityId));
            String json = t.opsForValue().get(key);
            if (json == null) return Optional.empty();
            List<TimelineEventDTO> list = codec.fromJson(json, LIST_TYPE);
            return Optional.ofNullable(list);
        } catch (Exception ex) {
            log.warn("TimelineCache: read failed merchant={} entity={}/{}", merchantId, entityType, entityId, ex);
            return Optional.empty();
        }
    }

    /**
     * Stores the timeline list in the cache under the entity's key.
     * No-op when Redis is unavailable.
     */
    public void put(Long merchantId, String entityType, Long entityId, List<TimelineEventDTO> dtos) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            String key = keyFactory.opsTimelineKey(str(merchantId), entityType, str(entityId));
            t.opsForValue().set(key, codec.toJson(dtos), Duration.ofSeconds(TTL_SECONDS));
        } catch (Exception ex) {
            log.warn("TimelineCache: write failed merchant={} entity={}/{}", merchantId, entityType, entityId, ex);
        }
    }

    /**
     * Evicts the cached timeline for the given entity.  Called after a new
     * timeline row has been appended so the next read reflects the update.
     * No-op when Redis is unavailable.
     */
    public void evict(Long merchantId, String entityType, Long entityId) {
        StringRedisTemplate t = template();
        if (t == null) return;
        try {
            t.delete(keyFactory.opsTimelineKey(str(merchantId), entityType, str(entityId)));
        } catch (Exception ex) {
            log.warn("TimelineCache: evict failed merchant={} entity={}/{}", merchantId, entityType, entityId, ex);
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private StringRedisTemplate template() {
        return templateProvider.getIfAvailable();
    }

    private static String str(Long value) {
        return value == null ? "null" : value.toString();
    }
}
