package com.linkstash.dto;

import java.time.Instant;

public record LinkStatsResponse(String shortCode, String originalUrl, long clickCount, Instant createdAt) {
}
