package com.linkstash.service;

import com.linkstash.exception.InvalidApiKeyException;
import com.linkstash.exception.MissingApiKeyException;
import com.linkstash.exception.RateLimitExceededException;
import com.linkstash.repository.ApiKeyRepository;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final RateLimiterService rateLimiterService;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, RateLimiterService rateLimiterService) {
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimiterService = rateLimiterService;
    }

    public void validateAndConsume(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new MissingApiKeyException();
        }
        apiKeyRepository.findByKeyValueAndActiveTrue(apiKey)
                .orElseThrow(InvalidApiKeyException::new);

        ConsumptionProbe probe = rateLimiterService.tryConsumeAndReturnProbe(apiKey);
        if (!probe.isConsumed()) {
            long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }
}
