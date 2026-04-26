package com.linkstash.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

import java.time.Instant;

public record CreateLinkRequest(
        @NotBlank @URL String url,
        @FutureOrPresent Instant expiresAt
) {
}
