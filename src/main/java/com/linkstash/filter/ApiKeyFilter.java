package com.linkstash.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkstash.ratelimit.RateLimiter;
import com.linkstash.repository.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

public class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(ApiKeyRepository apiKeyRepository, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only apply to POST /api/v1/links
        return !(request.getMethod().equals("POST") &&
                request.getRequestURI().equals("/api/v1/links"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String keyValue = request.getHeader("X-API-Key");

        if (keyValue == null || keyValue.isBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Missing X-API-Key header");
            return;
        }

        boolean valid = apiKeyRepository.findByKeyValueAndActiveTrue(keyValue).isPresent();
        if (!valid) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or inactive API key");
            return;
        }

        RateLimiter.RateResult result = rateLimiter.tryAcquire(keyValue);
        if (!result.isAllowed()) {
            response.setHeader("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("error", message));
    }
}
