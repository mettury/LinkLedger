package dev.linkledger.links;

import java.time.Instant;

public record LinkResponse(String code, String shortUrl, String url, Instant createdAt,
                           Instant expiresAt, String status) {
}
