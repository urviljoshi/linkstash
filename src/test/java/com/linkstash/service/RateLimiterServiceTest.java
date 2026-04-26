package com.linkstash.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterServiceTest {

    private RateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new RateLimiterService();
    }

    @Test
    void tryConsume_underLimit_returnsTrue() {
        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiterService.tryConsume("key-a")).isTrue();
        }
    }

    @Test
    void tryConsume_eleventhCall_returnsFalse() {
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsume("key-b");
        }
        assertThat(rateLimiterService.tryConsume("key-b")).isFalse();
    }

    @Test
    void tryConsume_overLimit_allSubsequentCallsReturnFalse() {
        for (int i = 0; i < 15; i++) {
            rateLimiterService.tryConsume("key-c");
        }
        // Calls 11-15 should have returned false. Let's verify by trying more.
        // Re-exhaust with a fresh key and check calls 11-15 explicitly.
        RateLimiterService service = new RateLimiterService();
        for (int i = 0; i < 10; i++) {
            service.tryConsume("key-c2");
        }
        for (int i = 0; i < 5; i++) {
            assertThat(service.tryConsume("key-c2")).isFalse();
        }
    }

    @Test
    void tryConsume_multiKeyIsolation_differentKeysDoNotShareBucket() {
        // Exhaust key-a
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsume("key-d");
        }
        assertThat(rateLimiterService.tryConsume("key-d")).isFalse();

        // key-e should still have tokens
        assertThat(rateLimiterService.tryConsume("key-e")).isTrue();
    }

    @Test
    void secondsUntilRefill_whenExhausted_returnsPositiveValue() {
        // Exhaust the bucket
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsume("key-f");
        }
        long seconds = rateLimiterService.secondsUntilRefill("key-f");
        assertThat(seconds).isGreaterThan(0);
        assertThat(seconds).isLessThanOrEqualTo(60);
    }
}
