package com.linkstash.service;

import io.github.bucket4j.ConsumptionProbe;
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
    void tryConsumeAndReturnProbe_underLimit_isConsumed() {
        String key = "key-under-limit";
        for (int i = 0; i < 10; i++) {
            ConsumptionProbe probe = rateLimiterService.tryConsumeAndReturnProbe(key);
            assertThat(probe.isConsumed())
                    .as("Call %d should be consumed", i + 1)
                    .isTrue();
        }
    }

    @Test
    void tryConsumeAndReturnProbe_atLimit_isNotConsumedAndReportsWaitTime() {
        String key = "key-at-limit";
        // Exhaust the 10 tokens
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsumeAndReturnProbe(key);
        }
        // 11th call must be rejected
        ConsumptionProbe probe = rateLimiterService.tryConsumeAndReturnProbe(key);
        assertThat(probe.isConsumed()).isFalse();
        assertThat(probe.getNanosToWaitForRefill()).isGreaterThan(0);
    }

    @Test
    void tryConsumeAndReturnProbe_multiKeyIsolation_exhaustingOneKeyDoesNotAffectAnother() {
        String keyA = "key-isolation-a";
        String keyB = "key-isolation-b";

        // Exhaust key A
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsumeAndReturnProbe(keyA);
        }
        assertThat(rateLimiterService.tryConsumeAndReturnProbe(keyA).isConsumed()).isFalse();

        // Key B should still have its full quota
        assertThat(rateLimiterService.tryConsumeAndReturnProbe(keyB).isConsumed()).isTrue();
    }

    @Test
    void tryConsumeAndReturnProbe_exhaustedBucket_probeReportsPositiveNanosToWait() {
        String key = "key-refill-check";
        // Exhaust the bucket
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsumeAndReturnProbe(key);
        }
        ConsumptionProbe probe = rateLimiterService.tryConsumeAndReturnProbe(key);
        assertThat(probe.isConsumed()).isFalse();
        long secondsToWait = probe.getNanosToWaitForRefill() / 1_000_000_000L;
        assertThat(secondsToWait).isGreaterThan(0);
    }

    @Test
    void resetBucket_afterExhaustion_allowsFullQuotaAgain() {
        String key = "key-reset";
        // Exhaust the bucket
        for (int i = 0; i < 10; i++) {
            rateLimiterService.tryConsumeAndReturnProbe(key);
        }
        assertThat(rateLimiterService.tryConsumeAndReturnProbe(key).isConsumed()).isFalse();

        // Reset and verify the full quota is restored
        rateLimiterService.resetBucket(key);
        assertThat(rateLimiterService.tryConsumeAndReturnProbe(key).isConsumed()).isTrue();
    }
}
