package dev.linkledger.links;

import java.time.Instant;

public record Link(String code, String url, Instant createdAt, Instant expiresAt, boolean disabled) {
    public String status(Instant now) {
        if (disabled) {
            return "DISABLED";
        }
        return expiresAt != null && !expiresAt.isAfter(now) ? "EXPIRED" : "ACTIVE";
    }
}
