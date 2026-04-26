package com.linkstash.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Bucket createBucket() {
        Bandwidth limit = Bandwidth.classic(10, Refill.greedy(10, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket getBucket(String apiKey) {
        return buckets.computeIfAbsent(apiKey, k -> createBucket());
    }

    /**
     * Attempts to consume one token. Returns the probe so callers can inspect
     * whether the request was allowed and, if not, how long to wait.
     */
    public ConsumptionProbe tryConsumeAndReturnProbe(String apiKey) {
        return getBucket(apiKey).tryConsumeAndReturnRemaining(1);
    }

    /**
     * Removes the bucket for the given key so it is re-created fresh on the next request.
     * Intended for use in tests only.
     */
    public void resetBucket(String apiKey) {
        buckets.remove(apiKey);
    }
}
