package com.linkstash.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkstash.domain.Link;
import com.linkstash.dto.CreateLinkRequest;
import com.linkstash.repository.LinkRepository;
import com.linkstash.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LinkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LinkRepository linkRepository;

    @Autowired
    private RateLimiterService rateLimiterService;

    // ---- Existing tests (updated with X-API-Key) ----

    @Test
    void createLink_returnsShortCode() throws Exception {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com", null);

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
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
                        .header("X-API-Key", "test-key-12345")
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

    // ---- API-key enforcement ----

    @Test
    void createLink_missingApiKey_returns401() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://example.com");

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createLink_invalidApiKey_returns401() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://example.com");

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "bogus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createLink_validApiKey_returns201() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://valid-key-test.com");

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    // ---- Rate limiting ----

    @Test
    void createLink_rateLimitExceeded_returns429WithRetryAfter() throws Exception {
        rateLimiterService.clearBuckets();

        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://rate-limit-test.com");

        // Use rate-limit-test-key with a fresh bucket
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/links")
                            .header("X-API-Key", "rate-limit-test-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        // 11th request should be rate limited
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "rate-limit-test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        String retryAfter = result.getResponse().getHeader("Retry-After");
        assertThat(retryAfter).isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isPositive();
    }

    // ---- Expiration ----

    @Test
    void createLink_withFutureExpiresAt_includesExpiresAtInResponse() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://expiry-future-test.com");
        body.put("expiresAt", future.toString());

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void createLink_withoutExpiresAt_expiresAtIsNull() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://no-expiry-test.com");

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    @Test
    void createLink_expiresAtInPast_returns400() throws Exception {
        Instant past = Instant.now().minus(1, ChronoUnit.MINUTES);
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://past-expiry-test.com");
        body.put("expiresAt", past.toString());

        mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redirect_expiredLink_returns410WithBody() throws Exception {
        // Insert an already-expired link directly via repository to bypass @FutureOrPresent
        Link expiredLink = new Link();
        expiredLink.setShortCode("exprd001");
        expiredLink.setOriginalUrl("https://expired-url.com");
        expiredLink.setCreatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        expiredLink.setClickCount(0L);
        expiredLink.setExpiresAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        linkRepository.save(expiredLink);

        mockMvc.perform(get("/exprd001"))
                .andExpect(status().isGone())
                .andExpect(content().string(containsString("link expired")));
    }

    @Test
    void redirect_futurExpiresAt_returns302() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://redirect-future-test.com");
        body.put("expiresAt", future.toString());

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void redirect_noExpiresAt_returns302() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://redirect-no-expiry-test.com");

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void getStats_includesExpiresAt_whenSet() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://stats-expiry-test.com");
        body.put("expiresAt", future.toString());

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void getStats_noApiKeyRequired_returns200() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://stats-no-key-test.com");

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        // GET stats without any API key header
        mockMvc.perform(get("/api/v1/links/{shortCode}/stats", shortCode))
                .andExpect(status().isOk());
    }

    @Test
    void redirect_noApiKeyRequired_returns302() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("url", "https://redirect-no-key-test.com");

        MvcResult createResult = mockMvc.perform(post("/api/v1/links")
                        .header("X-API-Key", "test-key-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String shortCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("shortCode").asText();

        // GET redirect without any API key header
        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }
}
