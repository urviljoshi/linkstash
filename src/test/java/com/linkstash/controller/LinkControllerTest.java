package com.linkstash.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.service.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LinkControllerTest {

    private static final String VALID_KEY = "test-key-12345";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void resetRateLimiter() {
        rateLimiterService.resetAll();
    }

    // -------------------------------------------------------------------------
    // Existing happy-path tests (updated with API key + expiresAt=null)
    // -------------------------------------------------------------------------

    @Test
    void createLink_returnsShortCode() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", VALID_KEY)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").isNotEmpty())
                .andExpect(jsonPath("$.shortUrl").isNotEmpty())
                .andExpect(jsonPath("$.originalUrl").value("https://example.com"));
    }

    @Test
    void getStats_returnsClickCount() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://stats-test.com", null);

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", VALID_KEY)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = createResult.getResponse().getContentAsString();
        String shortCode = objectMapper.readTree(responseBody).get("shortCode").asText();

        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value(shortCode))
                .andExpect(jsonPath("$.clickCount").isNumber());
    }

    // -------------------------------------------------------------------------
    // API key enforcement
    // -------------------------------------------------------------------------

    @Test
    void createLink_missingApiKey_returns401() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createLink_invalidApiKey_returns401() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", "bad-key-xyz")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createLink_validApiKey_returns201() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", VALID_KEY)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void redirect_noApiKey_works() throws Exception {
        // Create a link first
        String shortCode = createLink("https://redirect-test.com");

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void deleteLink_missingApiKey_returns401() throws Exception {
        String shortCode = createLink("https://delete-auth-test.com");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/links/{shortCode}", shortCode))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteLink_validApiKey_returns204() throws Exception {
        String shortCode = createLink("https://delete-ok-test.com");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/links/{shortCode}", shortCode)
                        .header("X-API-Key", VALID_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    void getStats_noApiKey_works() throws Exception {
        String shortCode = createLink("https://stats-auth-test.com");

        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk());
    }

    @Test
    void createLink_pastExpiresAt_returns400() throws Exception {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", past);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", VALID_KEY)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Rate limiting
    // -------------------------------------------------------------------------

    @Test
    void createLink_rateLimitExceeded_returns429() throws Exception {
        // Use a unique key so other tests don't interfere with the window
        String key = "rate-limit-test-key-429";
        ensureKeyExists(key);

        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);
        String body = objectMapper.writeValueAsString(request);

        // Send 10 requests — all should succeed
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-API-Key", key)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        // 11th should be rejected
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", key)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void createLink_rateLimitExceeded_hasRetryAfterHeader() throws Exception {
        String key = "rate-limit-test-key-header";
        ensureKeyExists(key);

        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);
        String body = objectMapper.writeValueAsString(request);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/links")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-API-Key", key)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", key)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")));
    }

    // -------------------------------------------------------------------------
    // Link expiration
    // -------------------------------------------------------------------------

    @Test
    void redirect_noExpiry_redirects() throws Exception {
        String shortCode = createLink("https://no-expiry.com");

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void redirect_futureExpiry_redirects() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        String shortCode = createLinkWithExpiry("https://future-expiry.com", future);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void redirect_expiredLink_returns410() throws Exception {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        String shortCode = createLinkWithExpiry("https://expired-link.com", past);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone());
    }

    @Test
    void redirect_expiredLink_doesNotIncrementClickCount() throws Exception {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        String shortCode = createLinkWithExpiry("https://expired-click.com", past);

        // Attempt redirect — should return 410
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone());

        // Click count must remain 0
        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clickCount").value(0));
    }

    @Test
    void redirect_expiredLink_body() throws Exception {
        Instant past = Instant.parse("2020-01-01T00:00:00Z");
        String shortCode = createLinkWithExpiry("https://expired-body.com", past);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("link expired"));
    }

    @Test
    void stats_includesExpiresAt() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        String shortCode = createLinkWithExpiry("https://expiry-stats.com", future);

        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void stats_nullExpiresAt_whenNotSet() throws Exception {
        String shortCode = createLink("https://no-expiry-stats.com");

        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createLink(String url) throws Exception {
        CreateLinkRequest request = new CreateLinkRequest(url, null);
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", VALID_KEY)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("shortCode").asText();
    }

    /**
     * Creates a link via the API (future expiresAt only — past values are rejected by the service).
     */
    private String createLinkWithExpiry(String url, Instant expiresAt) throws Exception {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            return createExpiredLinkDirect(url, expiresAt);
        }
        CreateLinkRequest request = new CreateLinkRequest(url, expiresAt);
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-API-Key", VALID_KEY)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("shortCode").asText();
    }

    /**
     * Inserts an already-expired link directly via the repository, bypassing service
     * validation (which forbids past expiresAt on creation).
     */
    @Autowired
    private com.linkstash.repository.LinkRepository linkRepository;

    private String createExpiredLinkDirect(String url, Instant expiresAt) {
        com.linkstash.domain.Link link = new com.linkstash.domain.Link();
        link.setShortCode(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        link.setOriginalUrl(url);
        link.setCreatedAt(Instant.now());
        link.setClickCount(0L);
        link.setExpiresAt(expiresAt);
        return linkRepository.save(link).getShortCode();
    }

    /**
     * Inserts a test API key directly via the repository if not already present.
     * Uses a separate Spring context bean — simpler than calling an admin endpoint
     * that doesn't exist yet.
     */
    @Autowired
    private com.linkstash.repository.ApiKeyRepository apiKeyRepository;

    private void ensureKeyExists(String keyValue) {
        if (apiKeyRepository.findByKeyValueAndActiveTrue(keyValue).isEmpty()) {
            com.linkstash.domain.ApiKey k = new com.linkstash.domain.ApiKey();
            k.setKeyValue(keyValue);
            k.setName(keyValue);
            k.setCreatedAt(Instant.now());
            k.setActive(true);
            apiKeyRepository.save(k);
        }
    }
}
