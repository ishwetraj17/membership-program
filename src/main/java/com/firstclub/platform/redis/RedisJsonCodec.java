package com.firstclub.platform.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Jackson-backed codec for serialising/deserialising values stored in Redis.
 *
 * <p>All Redis values are stored as UTF-8 JSON strings.  This class wraps
 * the shared {@link ObjectMapper} bean and provides three usage patterns:
 *
 * <ul>
 *   <li>{@link #toJson(Object)} — serialise; throws on failure.</li>
 *   <li>{@link #fromJson(String, Class)} / {@link #fromJson(String, TypeReference)} — deserialise;
 *       throws on failure.</li>
 *   <li>{@link #tryFromJson(String, Class)} — deserialise; returns {@link Optional#empty()} on
 *       any error (safe for cache reads where corruption is acceptable to ignore).</li>
 * </ul>
 *
 * <p>Logging: serialisation exceptions are logged at {@code WARN} level with the
 * target type name to aid debugging without exposing potentially-sensitive value content.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisJsonCodec {

    private final ObjectMapper objectMapper;

    /**
     * Serialises {@code value} to a JSON string.
     *
     * @param value the object to serialise (must not be {@code null})
     * @return JSON representation
     * @throws RedisCodecException if serialisation fails
     */
    public <T> String toJson(T value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            String typeName = (value != null) ? value.getClass().getSimpleName() : "null";
            log.warn("Redis codec: failed to serialise {}", typeName, e);
            throw new RedisCodecException("Failed to serialise " + typeName + " to JSON", e);
        }
    }

    /**
     * Deserialises {@code json} to an instance of {@code type}.
     *
     * @param json  the JSON string (may be {@code null}; returns {@code null} if so)
     * @param type  target class
     * @return deserialised instance, or {@code null} if {@code json} is {@code null}
     * @throws RedisCodecException if deserialisation fails
     */
    public <T> T fromJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Redis codec: failed to deserialise JSON to {}", type.getSimpleName(), e);
            throw new RedisCodecException("Failed to deserialise JSON to " + type.getSimpleName(), e);
        }
    }

    /**
     * Deserialises {@code json} using a Jackson {@link TypeReference} (for generic types).
     *
     * @param json    the JSON string (may be {@code null}; returns {@code null} if so)
     * @param typeRef target type reference
     * @return deserialised instance, or {@code null} if {@code json} is {@code null}
     * @throws RedisCodecException if deserialisation fails
     */
    public <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Redis codec: failed to deserialise JSON to {}", typeRef.getType().getTypeName(), e);
            throw new RedisCodecException("Failed to deserialise JSON to " + typeRef.getType().getTypeName(), e);
        }
    }

    /**
     * Attempts to deserialise {@code json} to {@code type}, returning
     * {@link Optional#empty()} if {@code json} is null, blank, or malformed.
     *
     * <p>This variant is safe to use for hot-cache reads: if the cached value
     * is corrupt or absent, the caller should fall back to the source of truth.
     *
     * @param json the JSON string
     * @param type target class
     * @return populated {@link Optional} on success, empty on any error
     */
    public <T> Optional<T> tryFromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("Redis codec: non-fatal deserialisation failure for type {} — cache miss treated as empty",
                    type.getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Unchecked exception thrown when JSON serialisation or deserialisation fails.
     */
    public static final class RedisCodecException extends RuntimeException {
        public RedisCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
