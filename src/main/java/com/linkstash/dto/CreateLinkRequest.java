package com.linkstash.dto;

import java.time.Instant;

public record CreateLinkRequest(String url, Instant expiresAt) {
}
