package com.linkstash.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkstash.filter.ApiKeyFilter;
import com.linkstash.ratelimit.RateLimiter;
import com.linkstash.repository.ApiKeyRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
            ApiKeyRepository apiKeyRepository,
            RateLimiter rateLimiter,
            ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ApiKeyFilter(apiKeyRepository, rateLimiter, objectMapper));
        bean.addUrlPatterns("/api/v1/links");
        bean.setOrder(1);
        return bean;
    }
}
