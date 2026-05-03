package com.linkstash.service;

import com.linkstash.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RateLimiterService {

    static final int MAX_REQUESTS = 10;
    static final long WINDOW_SECONDS = 60;

    private record Window(long count, Instant windowStart) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiterService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Clears all rate-limit windows.
     * <p><strong>@VisibleForTesting</strong> — do not call from production code.</p>
     */
    public void resetAll() {
        windows.clear();
    }

    /**
     * Checks and increments the request count for the given API key.
     * Throws {@link RateLimitExceededException} if the limit is exceeded.
     */
    public void checkAndIncrement(String apiKey) {
        Instant now = Instant.now(clock);

        // Capture any rejection inside the atomic compute() block via a side-channel
        // so we can throw outside the lambda — keeping control flow explicit.
        AtomicReference<RateLimitExceededException> rejection = new AtomicReference<>();

        windows.compute(apiKey, (key, existing) -> {
            if (existing == null || now.isAfter(existing.windowStart().plusSeconds(WINDOW_SECONDS))) {
                // No window yet, or current window has expired — start a fresh one.
                return new Window(1, now);
            }
            if (existing.count() >= MAX_REQUESTS) {
                long secondsUsed = ChronoUnit.SECONDS.between(existing.windowStart(), now);
                long retryAfter = Math.max(1, WINDOW_SECONDS - secondsUsed);
                rejection.set(new RateLimitExceededException(retryAfter));
                return existing; // leave window unchanged
            }
            return new Window(existing.count() + 1, existing.windowStart());
        });

        RateLimitExceededException ex = rejection.get();
        if (ex != null) {
            throw ex;
        }
    }
}
