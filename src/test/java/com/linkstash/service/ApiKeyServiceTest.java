package com.linkstash.service;

import com.linkstash.domain.ApiKey;
import com.linkstash.exception.RateLimitExceededException;
import com.linkstash.exception.UnauthorizedException;
import com.linkstash.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private RateLimiterService rateLimiterService;

    @InjectMocks
    private ApiKeyService apiKeyService;

    @Test
    void validateAndConsume_nullKey_throwsUnauthorizedException() {
        assertThatThrownBy(() -> apiKeyService.validateAndConsume(null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void validateAndConsume_blankKey_throwsUnauthorizedException() {
        assertThatThrownBy(() -> apiKeyService.validateAndConsume("   "))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void validateAndConsume_unknownKey_throwsUnauthorizedException() {
        when(apiKeyRepository.findByKeyValueAndActiveTrue("unknown-key"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.validateAndConsume("unknown-key"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void validateAndConsume_inactiveKey_throwsUnauthorizedException() {
        // Repository already filters by active=true, so returns empty for inactive key
        when(apiKeyRepository.findByKeyValueAndActiveTrue("inactive-key"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.validateAndConsume("inactive-key"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void validateAndConsume_rateLimitExceeded_throwsRateLimitExceededException() {
        ApiKey apiKey = buildActiveApiKey("valid-key");
        when(apiKeyRepository.findByKeyValueAndActiveTrue("valid-key"))
                .thenReturn(Optional.of(apiKey));
        when(rateLimiterService.tryConsume("valid-key")).thenReturn(false);
        when(rateLimiterService.secondsUntilRefill("valid-key")).thenReturn(30L);

        assertThatThrownBy(() -> apiKeyService.validateAndConsume("valid-key"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void validateAndConsume_validKeyUnderLimit_doesNotThrow() {
        ApiKey apiKey = buildActiveApiKey("good-key");
        when(apiKeyRepository.findByKeyValueAndActiveTrue("good-key"))
                .thenReturn(Optional.of(apiKey));
        when(rateLimiterService.tryConsume("good-key")).thenReturn(true);

        assertThatNoException().isThrownBy(() -> apiKeyService.validateAndConsume("good-key"));
    }

    private ApiKey buildActiveApiKey(String keyValue) {
        ApiKey key = new ApiKey();
        key.setKeyValue(keyValue);
        key.setName("Test Key");
        key.setCreatedAt(Instant.now());
        key.setActive(true);
        return key;
    }
}
