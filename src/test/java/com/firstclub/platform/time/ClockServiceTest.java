package com.firstclub.platform.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static java.time.temporal.ChronoUnit.SECONDS;

@DisplayName("SystemClockService")
class ClockServiceTest {

    // ── Frozen-clock constructor (test path) ──────────────────────────────────

    @Test
    @DisplayName("now() returns the instant from the injected clock")
    void now_returnsFrozenInstant() {
        Instant frozen = Instant.parse("2025-06-15T10:00:00Z");
        ClockService clock = new SystemClockService(Clock.fixed(frozen, ZoneOffset.UTC));

        assertThat(clock.now()).isEqualTo(frozen);
    }

    @Test
    @DisplayName("todayUtc() returns the UTC date of the frozen instant")
    void todayUtc_returnsFrozenDate() {
        Instant frozen = Instant.parse("2025-03-31T23:59:59Z");
        ClockService clock = new SystemClockService(Clock.fixed(frozen, ZoneOffset.UTC));

        assertThat(clock.todayUtc()).isEqualTo(LocalDate.of(2025, 3, 31));
    }

    @Test
    @DisplayName("todayUtc() is not affected by a non-UTC local time zone on the JVM")
    void todayUtc_ignoresJvmDefaultZone() {
        // Midnight UTC on April 1st is still March 31st in UTC-5
        Instant frozen = Instant.parse("2025-04-01T00:00:00Z");
        ClockService clock = new SystemClockService(Clock.fixed(frozen, ZoneOffset.UTC));

        assertThat(clock.todayUtc()).isEqualTo(LocalDate.of(2025, 4, 1));
    }

    @Test
    @DisplayName("nowUtc() returns the UTC LocalDateTime of the frozen instant")
    void nowUtc_returnsFrozenDateTime() {
        Instant frozen = Instant.parse("2025-06-15T14:30:00Z");
        ClockService clock = new SystemClockService(Clock.fixed(frozen, ZoneOffset.UTC));

        assertThat(clock.nowUtc()).isEqualTo(LocalDateTime.of(2025, 6, 15, 14, 30, 0));
    }

    @Test
    @DisplayName("rawClock() returns the underlying clock instance")
    void rawClock_returnsUnderlyingClock() {
        Clock fixed = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        ClockService clock = new SystemClockService(fixed);

        assertThat(clock.rawClock()).isSameAs(fixed);
    }

    // ── Default constructor (production path) ────────────────────────────────

    @Test
    @DisplayName("default constructor now() is close to the real wall-clock time")
    void defaultConstructor_nowIsCloseToWallClock() {
        ClockService clock = new SystemClockService();
        Instant before = Instant.now();

        Instant result = clock.now();

        Instant after = Instant.now();
        assertThat(result).isBetween(before, after);
    }

    @Test
    @DisplayName("default constructor todayUtc() returns today in UTC")
    void defaultConstructor_todayIsToday() {
        ClockService clock = new SystemClockService();

        assertThat(clock.todayUtc()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
    }

    // ── UTC constant ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ClockService.UTC constant is the UTC zone")
    void utcConstant_isUtcZone() {
        assertThat(ClockService.UTC.getId()).isEqualTo("UTC");
    }
}
