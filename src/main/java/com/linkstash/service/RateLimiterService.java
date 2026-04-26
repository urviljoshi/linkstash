package com.linkstash.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimiterService {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(1, java.util.concurrent.TimeUnit.HOURS)
            .build();

    private Bucket createNewBucket() {
        Bandwidth limit = Bandwidth.classic(10, Refill.greedy(10, Duration.ofSeconds(60)));
        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    private Bucket getBucket(String key) {
        return buckets.get(key, k -> createNewBucket());
    }

    public boolean tryConsume(String key) {
        return getBucket(key).tryConsume(1);
    }

    public long secondsUntilRefill(String key) {
        long nanosToWait = getBucket(key).estimateAbilityToConsume(1).getNanosToWaitForRefill();
        return (nanosToWait + 999_999_999L) / 1_000_000_000L;
    }

    public void clearBuckets() {
        buckets.invalidateAll();
    }
}
