package com.linkstash.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkstash.domain.Link;
import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.ratelimit.RateLimiter;
import com.linkstash.repository.LinkRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LinkExpiryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private LinkRepository linkRepository;

    private static final String API_KEY = "test-key-12345";

    @BeforeEach
    void resetRateLimiter() {
        // Each test gets a clean rate-limit window; the RateLimiter is a singleton
        // bean shared across the Spring test context.
        rateLimiter.reset();
    }

    private String createLink(Instant expiresAt) throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com/page", expiresAt);
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("shortCode").asText();
    }

    /** Insert a link directly, bypassing service validation (used to seed past-expiry links). */
    private String insertExpiredLink() {
        Link link = new Link();
        link.setShortCode("exprd" + System.nanoTime() % 1_000L); // unique 8-char code
        link.setOriginalUrl("https://example.com/expired");
        link.setCreatedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        link.setClickCount(0L);
        link.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        return linkRepository.save(link).getShortCode();
    }

    @Test
    void activeLink_redirects() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        String shortCode = createLink(future);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void expiredLink_returns410() throws Exception {
        // Insert directly — the API rejects past expiresAt (400), so we seed via repo.
        String shortCode = insertExpiredLink();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("link expired"));
    }

    @Test
    void expiredLink_doesNotIncrementClickCount() throws Exception {
        String shortCode = insertExpiredLink();
        long clicksBefore = linkRepository.findByShortCode(shortCode).orElseThrow().getClickCount();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone());

        long clicksAfter = linkRepository.findByShortCode(shortCode).orElseThrow().getClickCount();
        assertThat(clicksAfter).isEqualTo(clicksBefore);
    }

    @Test
    void noExpiry_redirectsNormally() throws Exception {
        String shortCode = createLink(null);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void createLink_withPastExpiresAt_returns400() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest(
                "https://example.com", Instant.now().minus(1, ChronoUnit.HOURS));
        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postWithoutApiKey_returns401() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithInvalidApiKey_returns401() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);
        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getRedirect_doesNotRequireApiKey() throws Exception {
        String shortCode = createLink(null);
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void getStats_doesNotRequireApiKey() throws Exception {
        String shortCode = createLink(null);
        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isEmpty());
    }

    @Test
    void statsIncludeExpiresAt() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        String shortCode = createLink(future);

        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void rateLimitExceeded_returns429WithRetryAfterHeader() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://rate-limit-test.com", null);
        String body = objectMapper.writeValueAsString(request);

        // Send MAX_REQUESTS successful requests.
        for (int i = 0; i < RateLimiter.MAX_REQUESTS; i++) {
            mockMvc.perform(post("/api/v1/links")
                            .header("X-API-Key", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        // The next request must be rejected with 429 + Retry-After header.
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).as("Retry-After header must be present").isNotNull();
        assertThat(Long.parseLong(retryAfter)).as("Retry-After must be positive").isPositive();
    }
}
