package com.linkstash.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkstash.dto.CreateLinkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

    private static final String API_KEY = "test-key-12345";

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

    @Test
    void activeLink_redirects() throws Exception {
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);
        String shortCode = createLink(future);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
    }

    @Test
    void expiredLink_returns410() throws Exception {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String shortCode = createLink(past);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("link expired"));
    }

    @Test
    void noExpiry_redirectsNormally() throws Exception {
        String shortCode = createLink(null);

        mockMvc.perform(get("/{shortCode}", shortCode))
                .andExpect(status().isFound());
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
        // GET /{shortCode} has no API key — should still work (302)
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
}
