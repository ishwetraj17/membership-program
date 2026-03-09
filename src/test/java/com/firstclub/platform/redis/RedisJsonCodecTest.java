package com.firstclub.platform.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RedisJsonCodec}.
 *
 * <p>Verifies serialisation round-trips, error handling, and the
 * {@code tryFromJson} safe-parse path used in cache-read fallbacks.
 * No Redis connection required.
 */
@DisplayName("RedisJsonCodec — Unit Tests")
class RedisJsonCodecTest {

    private RedisJsonCodec codec;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        codec = new RedisJsonCodec(objectMapper);
    }

    // ── Test fixture ──────────────────────────────────────────────────────────

    record SampleDTO(String name, int value, boolean active) {}

    // ── toJson ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("toJson")
    class ToJson {

        @Test
        @DisplayName("serialises a simple record to JSON")
        void serialisesRecordToJson() {
            SampleDTO dto = new SampleDTO("test", 42, true);
            String json = codec.toJson(dto);
            assertThat(json)
                    .contains("\"name\":\"test\"")
                    .contains("\"value\":42")
                    .contains("\"active\":true");
        }

        @Test
        @DisplayName("serialises a list to a JSON array")
        void serialisesListToJsonArray() {
            List<String> list = List.of("alpha", "beta", "gamma");
            String json = codec.toJson(list);
            assertThat(json).isEqualTo("[\"alpha\",\"beta\",\"gamma\"]");
        }

        @Test
        @DisplayName("serialises null to 'null' JSON literal")
        void serialisesNullValue() {
            String json = codec.toJson(null);
            assertThat(json).isEqualTo("null");
        }
    }

    // ── fromJson (class) ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("fromJson(String, Class)")
    class FromJsonByClass {

        @Test
        @DisplayName("deserialises back to the original DTO")
        void deserialisesRoundTrip() {
            SampleDTO original = new SampleDTO("round-trip", 99, false);
            String json = codec.toJson(original);
            SampleDTO result = codec.fromJson(json, SampleDTO.class);
            assertThat(result).isEqualTo(original);
        }

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNullInput() {
            SampleDTO result = codec.fromJson(null, SampleDTO.class);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("throws RedisCodecException for malformed JSON")
        void throwsForMalformedJson() {
            assertThatThrownBy(() -> codec.fromJson("{not-valid-json", SampleDTO.class))
                    .isInstanceOf(RedisJsonCodec.RedisCodecException.class);
        }

        @Test
        @DisplayName("throws RedisCodecException for type-incompatible JSON")
        void throwsForIncompatibleType() {
            // "Alamos" is a string — cannot be deserialized to SampleDTO
            assertThatThrownBy(() -> codec.fromJson("\"Alamos\"", SampleDTO.class))
                    .isInstanceOf(RedisJsonCodec.RedisCodecException.class);
        }
    }

    // ── fromJson (TypeReference) ──────────────────────────────────────────────

    @Nested
    @DisplayName("fromJson(String, TypeReference)")
    class FromJsonByTypeRef {

        @Test
        @DisplayName("deserialises generic list via TypeReference")
        void deserialisesListViaTypeRef() {
            List<SampleDTO> originals = List.of(
                    new SampleDTO("a", 1, true),
                    new SampleDTO("b", 2, false));
            String json = codec.toJson(originals);
            List<SampleDTO> result = codec.fromJson(json, new TypeReference<List<SampleDTO>>() {});
            assertThat(result).hasSize(2).isEqualTo(originals);
        }

        @Test
        @DisplayName("returns null for null input")
        void returnsNullForNullInput() {
            List<SampleDTO> result = codec.fromJson(null, new TypeReference<List<SampleDTO>>() {});
            assertThat(result).isNull();
        }
    }

    // ── tryFromJson ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("tryFromJson — safe cache-read variant")
    class TryFromJson {

        @Test
        @DisplayName("returns populated Optional for valid JSON")
        void returnsPopulatedOptional() {
            SampleDTO dto = new SampleDTO("safe", 7, true);
            String json = codec.toJson(dto);
            Optional<SampleDTO> result = codec.tryFromJson(json, SampleDTO.class);
            assertThat(result).isPresent().contains(dto);
        }

        @Test
        @DisplayName("returns empty Optional for null input")
        void returnsEmptyForNull() {
            assertThat(codec.tryFromJson(null, SampleDTO.class)).isEmpty();
        }

        @Test
        @DisplayName("returns empty Optional for blank input")
        void returnsEmptyForBlankString() {
            assertThat(codec.tryFromJson("   ", SampleDTO.class)).isEmpty();
        }

        @Test
        @DisplayName("returns empty Optional for malformed JSON — does not throw")
        void returnsEmptyForMalformedJson() {
            Optional<SampleDTO> result = codec.tryFromJson("{broken", SampleDTO.class);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty Optional for incompatible type — does not throw")
        void returnsEmptyForIncompatibleType() {
            Optional<SampleDTO> result = codec.tryFromJson("\"just-a-string\"", SampleDTO.class);
            assertThat(result).isEmpty();
        }
    }
}
