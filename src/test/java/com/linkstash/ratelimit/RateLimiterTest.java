package com.linkstash.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter();
    }

    @Test
    void underLimit_allowed() {
        for (int i = 0; i < RateLimiter.MAX_REQUESTS; i++) {
            assertThat(rateLimiter.tryAcquire("key-a").isAllowed()).isTrue();
        }
    }

    @Test
    void atLimit_lastRequestAllowed() {
        RateLimiter.RateResult result = null;
        for (int i = 0; i < RateLimiter.MAX_REQUESTS; i++) {
            result = rateLimiter.tryAcquire("key-b");
        }
        assertThat(result).isNotNull();
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    void overLimit_denied() {
        for (int i = 0; i < RateLimiter.MAX_REQUESTS; i++) {
            rateLimiter.tryAcquire("key-c");
        }
        RateLimiter.RateResult result = rateLimiter.tryAcquire("key-c");
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getRetryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void multiKeyIsolation_separateLimits() {
        // exhaust key-d
        for (int i = 0; i < RateLimiter.MAX_REQUESTS; i++) {
            rateLimiter.tryAcquire("key-d");
        }
        assertThat(rateLimiter.tryAcquire("key-d").isAllowed()).isFalse();
        // key-e is independent
        assertThat(rateLimiter.tryAcquire("key-e").isAllowed()).isTrue();
    }
}
