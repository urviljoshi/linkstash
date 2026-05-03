package com.linkstash.interceptor;

import com.linkstash.exception.RateLimitExceededException;
import com.linkstash.repository.ApiKeyRepository;
import com.linkstash.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;
    private final RateLimiterService rateLimiterService;

    public ApiKeyInterceptor(ApiKeyRepository apiKeyRepository, RateLimiterService rateLimiterService) {
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // Enforce on POST (create) and DELETE (delete); pass GET (stats) through
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return true;
        }

        String keyValue = request.getHeader(API_KEY_HEADER);
        if (keyValue == null || keyValue.isBlank()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-API-Key header");
            return false;
        }
        // Reject oversized values before hitting the DB (column limit is VARCHAR(64))
        if (keyValue.length() > 64) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or inactive API key");
            return false;
        }

        boolean valid = apiKeyRepository.findByKeyValueAndActiveTrue(keyValue).isPresent();
        if (!valid) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or inactive API key");
            return false;
        }

        try {
            rateLimiterService.checkAndIncrement(keyValue);
        } catch (RateLimitExceededException ex) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate limit exceeded\",\"retryAfterSeconds\":"
                    + ex.getRetryAfterSeconds() + "}");
            return false;
        }

        return true;
    }
}
