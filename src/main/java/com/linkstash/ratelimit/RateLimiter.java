package com.linkstash.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window in-memory rate limiter: 10 requests per 60-second window per API key.
 */
@Component
public class RateLimiter {

    public static final int MAX_REQUESTS = 10;
    public static final long WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();

    /**
     * Attempt to consume one token for the given key.
     *
     * @return a {@link RateResult} indicating whether the request is allowed and, if not,
     *         how many seconds until the window resets.
     */
    public RateResult tryAcquire(String apiKey) {
        WindowState state = windows.compute(apiKey, (k, existing) -> {
            long t = Instant.now().getEpochSecond();
            if (existing == null || t >= existing.windowStart + WINDOW_SECONDS) {
                return new WindowState(t, new AtomicInteger(0));
            }
            return existing;
        });

        int count = state.counter.incrementAndGet();
        if (count <= MAX_REQUESTS) {
            return RateResult.allowed();
        }
        long now = Instant.now().getEpochSecond();
        long retryAfter = (state.windowStart + WINDOW_SECONDS) - now;
        // Opportunistically evict entries whose window has fully expired.
        windows.entrySet().removeIf(e -> now >= e.getValue().windowStart + WINDOW_SECONDS);
        return RateResult.denied(Math.max(retryAfter, 1));
    }

    /** Clears all window state. Intended for use in tests only. */
    public void reset() {
        windows.clear();
    }

    public static class WindowState {
        final long windowStart;
        final AtomicInteger counter;

        WindowState(long windowStart, AtomicInteger counter) {
            this.windowStart = windowStart;
            this.counter = counter;
        }
    }

    public static class RateResult {
        private final boolean allowed;
        private final long retryAfterSeconds;

        private RateResult(boolean allowed, long retryAfterSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public static RateResult allowed() {
            return new RateResult(true, 0);
        }

        public static RateResult denied(long retryAfterSeconds) {
            return new RateResult(false, retryAfterSeconds);
        }

        public boolean isAllowed() { return allowed; }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }
}
