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

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LinkControllerTest {

    private static final String TEST_API_KEY = "test-key-12345";
    private static final String RATE_LIMIT_API_KEY = "rate-limit-test-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimiterService rateLimiterService;

    @BeforeEach
    void resetRateLimitBucket() {
        rateLimiterService.resetBucket(RATE_LIMIT_API_KEY);
    }

    @Test
    void createLink_returnsShortCode() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
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
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
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

    @Test
    void createLink_missingApiKey_returns401() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing or invalid API key"));
    }

    @Test
    void createLink_invalidApiKey_returns401() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing or invalid API key"));
    }

    @Test
    void createLink_rateLimitExceeded_returns429WithRetryAfter() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://ratelimit-test.com", null);
        String body = objectMapper.writeValueAsString(request);

        // Exhaust the 10-request limit
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/links")
                            .header("X-API-Key", RATE_LIMIT_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        // 11th request should be rate limited
        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", RATE_LIMIT_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("rate limit exceeded"));
    }

    @Test
    void redirect_expiredLink_returns410() throws Exception {
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.HOURS);
        CreateLinkRequest request = new CreateLinkRequest("https://expired-link-test.com", pastExpiry);

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("link expired"));
    }

    @Test
    void redirect_activeLink_noExpiry_returns302() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://no-expiry-test.com", null);

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void redirect_futureExpiry_returns302() throws Exception {
        Instant futureExpiry = Instant.now().plus(1, ChronoUnit.HOURS);
        CreateLinkRequest request = new CreateLinkRequest("https://future-expiry-test.com", futureExpiry);

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void getStats_includesExpiresAt() throws Exception {
        Instant futureExpiry = Instant.now().plus(2, ChronoUnit.HOURS);
        CreateLinkRequest request = new CreateLinkRequest("https://stats-expiry-test.com", futureExpiry);

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").value(notNullValue()));
    }

    @Test
    void getStats_noApiKeyRequired() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://stats-no-auth-test.com", null);

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        // GET stats without X-API-Key header should succeed
        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk());
    }

    @Test
    void redirect_noApiKeyRequired() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://redirect-no-auth-test.com", null);

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", TEST_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        // GET redirect without X-API-Key header should succeed
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }
}
