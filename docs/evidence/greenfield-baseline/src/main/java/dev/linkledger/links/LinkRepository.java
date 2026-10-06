package dev.linkledger.links;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LinkRepository {
    private final JdbcTemplate jdbc;

    public LinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Link> find(String code) {
        return jdbc.query("SELECT * FROM links WHERE code = ?", (rs, row) -> map(rs), code).stream().findFirst();
    }

    public void insert(Link link) {
        jdbc.update("INSERT INTO links(code, destination_url, created_at, expires_at, disabled) VALUES (?, ?, ?, ?, FALSE)",
                link.code(), link.url(), sqlTime(link.createdAt()), sqlTime(link.expiresAt()));
        jdbc.update("INSERT INTO link_metrics(code, total_redirects) VALUES (?, 0)", link.code());
    }

    public Optional<IdempotencyRecord> findIdempotency(String keyHash) {
        return jdbc.query("SELECT request_hash, code FROM idempotency_records WHERE key_hash = ?",
                (rs, row) -> new IdempotencyRecord(rs.getString("request_hash"), rs.getString("code")), keyHash)
                .stream().findFirst();
    }

    public void insertIdempotency(String keyHash, String requestHash, Link link) {
        jdbc.update("INSERT INTO idempotency_records(key_hash, request_hash, code, created_at) VALUES (?, ?, ?, ?)",
                keyHash, requestHash, link.code(), sqlTime(link.createdAt()));
    }

    public boolean disable(String code) {
        return jdbc.update("UPDATE links SET disabled = TRUE WHERE code = ?", code) != 0;
    }

    private Link map(ResultSet rs) throws SQLException {
        OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
        return new Link(rs.getString("code"), rs.getString("destination_url"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                expires == null ? null : expires.toInstant(), rs.getBoolean("disabled"));
    }

    private OffsetDateTime sqlTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public record IdempotencyRecord(String requestHash, String code) {
    }
}
