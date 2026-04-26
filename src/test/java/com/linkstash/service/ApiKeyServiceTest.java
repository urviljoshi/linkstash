package com.linkstash.service;

import com.linkstash.domain.ApiKey;
import com.linkstash.exception.InvalidApiKeyException;
import com.linkstash.exception.MissingApiKeyException;
import com.linkstash.exception.RateLimitExceededException;
import com.linkstash.repository.ApiKeyRepository;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private RateLimiterService rateLimiterService;

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyRepository, rateLimiterService);
    }

    @Test
    void validate_nullKey_throwsMissingApiKeyException() {
        assertThatThrownBy(() -> apiKeyService.validateAndConsume(null))
                .isInstanceOf(MissingApiKeyException.class);
    }

    @Test
    void validate_blankKey_throwsMissingApiKeyException() {
        assertThatThrownBy(() -> apiKeyService.validateAndConsume("   "))
                .isInstanceOf(MissingApiKeyException.class);
    }

    @Test
    void validate_unknownKey_throwsInvalidApiKeyException() {
        when(apiKeyRepository.findByKeyValueAndActiveTrue("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.validateAndConsume("unknown"))
                .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    void validate_rateLimitExceeded_throwsRateLimitExceededException() {
        ApiKey validKey = buildApiKey("valid-key");
        when(apiKeyRepository.findByKeyValueAndActiveTrue("valid-key")).thenReturn(Optional.of(validKey));

        ConsumptionProbe exhaustedProbe = mock(ConsumptionProbe.class);
        when(exhaustedProbe.isConsumed()).thenReturn(false);
        // 42 seconds in nanoseconds
        when(exhaustedProbe.getNanosToWaitForRefill()).thenReturn(42_000_000_000L);
        when(rateLimiterService.tryConsumeAndReturnProbe("valid-key")).thenReturn(exhaustedProbe);

        assertThatThrownBy(() -> apiKeyService.validateAndConsume("valid-key"))
                .isInstanceOf(RateLimitExceededException.class)
                .extracting(e -> ((RateLimitExceededException) e).getRetryAfterSeconds())
                .isEqualTo(43L); // 42 + 1
    }

    @Test
    void validate_validKey_underLimit_succeeds() {
        ApiKey validKey = buildApiKey("valid-key");
        when(apiKeyRepository.findByKeyValueAndActiveTrue("valid-key")).thenReturn(Optional.of(validKey));

        ConsumptionProbe allowedProbe = mock(ConsumptionProbe.class);
        when(allowedProbe.isConsumed()).thenReturn(true);
        when(rateLimiterService.tryConsumeAndReturnProbe("valid-key")).thenReturn(allowedProbe);

        assertThatCode(() -> apiKeyService.validateAndConsume("valid-key"))
                .doesNotThrowAnyException();
    }

    private ApiKey buildApiKey(String keyValue) {
        ApiKey key = new ApiKey();
        key.setKeyValue(keyValue);
        key.setName("Test");
        key.setCreatedAt(Instant.now());
        key.setActive(true);
        return key;
    }
}
