package dev.linkledger.analytics;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsRepository {
    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void increment(String code, Instant occurredAt) {
        OffsetDateTime time = occurredAt.atOffset(ZoneOffset.UTC);
        // Atomic SQL avoids lost increments. Monotonic timestamp handles out-of-order requests.
        jdbc.update("UPDATE link_metrics SET total_redirects = total_redirects + 1, "
                + "last_accessed_at = CASE WHEN last_accessed_at IS NULL OR last_accessed_at < ? "
                + "THEN ? ELSE last_accessed_at END WHERE code = ?", time, time, code);
    }

    public AnalyticsResponse find(String code) {
        return jdbc.queryForObject("SELECT total_redirects, last_accessed_at FROM link_metrics WHERE code = ?", (rs, row) -> {
            OffsetDateTime last = rs.getObject("last_accessed_at", OffsetDateTime.class);
            return new AnalyticsResponse(code, rs.getLong("total_redirects"), last == null ? null : last.toInstant(),
                    "Recorded GET resolutions; repeats and bots included; best effort.");
        }, code);
    }
}
