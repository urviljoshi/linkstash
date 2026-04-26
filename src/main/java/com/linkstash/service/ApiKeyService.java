package com.linkstash.service;

import com.linkstash.exception.RateLimitExceededException;
import com.linkstash.exception.UnauthorizedException;
import com.linkstash.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;

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
            throw new UnauthorizedException("API key is required");
        }

        apiKeyRepository.findByKeyValueAndActiveTrue(apiKey)
                .orElseThrow(() -> new UnauthorizedException("Invalid or inactive API key"));

        if (!rateLimiterService.tryConsume(apiKey)) {
            long retryAfter = rateLimiterService.secondsUntilRefill(apiKey);
            throw new RateLimitExceededException(retryAfter);
        }
    }
}
