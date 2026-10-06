package dev.linkledger.analytics;

import java.time.Instant;

public record AnalyticsResponse(String code, long totalRedirects, Instant lastAccessedAt, String measurement) {
}
