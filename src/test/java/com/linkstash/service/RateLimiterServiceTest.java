package com.linkstash.service;

import com.linkstash.exception.RateLimitExceededException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class RateLimiterServiceTest {

    private static final String KEY_A = "key-a";
    private static final String KEY_B = "key-b";

    @Test
    void underLimit_allowsRequest() {
        RateLimiterService svc = new RateLimiterService(Clock.systemUTC());

        // 9 calls — all should pass without exception
        for (int i = 0; i < 9; i++) {
            assertThatCode(() -> svc.checkAndIncrement(KEY_A)).doesNotThrowAnyException();
        }
    }

    @Test
    void atLimit_10thCallAllowed() {
        RateLimiterService svc = new RateLimiterService(Clock.systemUTC());

        for (int i = 0; i < 10; i++) {
            assertThatCode(() -> svc.checkAndIncrement(KEY_A)).doesNotThrowAnyException();
        }
    }

    @Test
    void overLimit_11thCallThrows() {
        RateLimiterService svc = new RateLimiterService(Clock.systemUTC());

        for (int i = 0; i < 10; i++) {
            svc.checkAndIncrement(KEY_A);
        }

        assertThatThrownBy(() -> svc.checkAndIncrement(KEY_A))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void overLimit_retryAfterIsPositive() {
        RateLimiterService svc = new RateLimiterService(Clock.systemUTC());

        for (int i = 0; i < 10; i++) {
            svc.checkAndIncrement(KEY_A);
        }

        RateLimitExceededException ex = catchThrowableOfType(
                () -> svc.checkAndIncrement(KEY_A), RateLimitExceededException.class);

        assertThat(ex.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(ex.getRetryAfterSeconds()).isLessThanOrEqualTo(60L);
    }

    @Test
    void windowReset_allowsAfterMinute() {
        // Use a controllable clock
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = Clock.fixed(now.get(), ZoneOffset.UTC);

        // We need a mutable clock — use a wrapper
        RateLimiterService svc = new RateLimiterService(new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        });

        // Fill the window
        for (int i = 0; i < 10; i++) {
            svc.checkAndIncrement(KEY_A);
        }

        // Confirm it's blocked
        assertThatThrownBy(() -> svc.checkAndIncrement(KEY_A))
                .isInstanceOf(RateLimitExceededException.class);

        // Advance clock by 61 seconds — past the 60-second window
        now.set(now.get().plusSeconds(61));

        // First call in new window must succeed
        assertThatCode(() -> svc.checkAndIncrement(KEY_A)).doesNotThrowAnyException();
    }

    @Test
    void multiKeyIsolation_keysDontShareCounters() {
        RateLimiterService svc = new RateLimiterService(Clock.systemUTC());

        // Exhaust KEY_A
        for (int i = 0; i < 10; i++) {
            svc.checkAndIncrement(KEY_A);
        }
        assertThatThrownBy(() -> svc.checkAndIncrement(KEY_A))
                .isInstanceOf(RateLimitExceededException.class);

        // KEY_B should be completely unaffected
        assertThatCode(() -> svc.checkAndIncrement(KEY_B)).doesNotThrowAnyException();
    }
}
