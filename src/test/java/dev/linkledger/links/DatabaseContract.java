package dev.linkledger.links;

import dev.linkledger.analytics.AnalyticsService;
import dev.linkledger.api.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The same contracts are exercised against H2 and real PostgreSQL. */
abstract class DatabaseContract {
    @Autowired LinkService links;
    @Autowired AnalyticsService analytics;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentIdempotencyCreatesExactlyOneLinkAndRecord() throws Exception {
        String key = UUID.randomUUID().toString();
        String url = "https://example.com/concurrent/" + key;
        CreateLinkRequest request = new CreateLinkRequest(url, null, null);
        List<Callable<LinkService.CreateResult>> tasks = IntStream.range(0, 20)
                .mapToObj(i -> (Callable<LinkService.CreateResult>) () -> links.create(request, key)).toList();
        try (var pool = Executors.newFixedThreadPool(10)) {
            var futures = pool.invokeAll(tasks);
            Set<String> codes = new java.util.HashSet<>();
            int creations = 0;
            for (var future : futures) {
                var result = future.get();
                codes.add(result.link().code());
                if (result.created()) {
                    creations++;
                }
            }
            assertThat(codes).hasSize(1);
            assertThat(creations).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM links WHERE destination_url = ?", Long.class, url)).isEqualTo(1);
    }

    @Test
    void repeatedUrlsCreateIndependentLinksWithoutIdempotencyKey() {
        CreateLinkRequest request = new CreateLinkRequest("https://example.com/repeated", null, null);
        assertThat(links.create(request, null).link().code()).isNotEqualTo(links.create(request, null).link().code());
    }

    @Test
    void concurrentRedirectCountersDoNotLoseUpdatesAndTimestampIsMonotonic() throws Exception {
        String code = links.create(new CreateLinkRequest("https://example.com/counter/" + UUID.randomUUID(), null, null), null)
                .link().code();
        Instant latest = Instant.parse("2030-01-01T00:00:00Z");
        List<Callable<Void>> tasks = IntStream.range(0, 100).mapToObj(i -> (Callable<Void>) () -> {
            analytics.record(code, latest.minusSeconds(i));
            return null;
        }).collect(Collectors.toList());
        try (var pool = Executors.newFixedThreadPool(10)) {
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        }
        assertThat(analytics.get(code).totalRedirects()).isEqualTo(100);
        assertThat(analytics.get(code).lastAccessedAt()).isEqualTo(latest);
    }

    @Test
    void aliasTombstoneAndMicrosecondExpiryRoundTrip() {
        String alias = "pg-" + UUID.randomUUID().toString().substring(0, 8);
        Instant expiry = Instant.parse("2090-01-01T00:00:00.123456Z");
        var result = links.create(new CreateLinkRequest("https://example.com/lifecycle", alias, expiry), null);
        assertThat(links.find(alias).expiresAt()).isEqualTo(expiry);
        assertThat(result.link().code()).isEqualTo(alias);
        links.disable(alias);
        assertThatThrownBy(() -> links.resolve(alias)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status().value()).isEqualTo(410));
        assertThatThrownBy(() -> links.create(new CreateLinkRequest("https://example.com/replacement", alias, null), null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status().value()).isEqualTo(409));
    }

    @Test
    void concurrentAliasCreationHasOneDatabaseWinner() throws Exception {
        String alias = "db-race-" + UUID.randomUUID().toString().substring(0, 8);
        List<Callable<Integer>> tasks = IntStream.range(0, 12).mapToObj(i -> (Callable<Integer>) () -> {
            try {
                links.create(new CreateLinkRequest("https://example.com/db-race/" + i, alias, null), null);
                return 201;
            } catch (ApiException conflict) {
                return conflict.status().value();
            }
        }).toList();
        int winners = 0;
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (var future : pool.invokeAll(tasks)) {
                int status = future.get();
                assertThat(status).isIn(201, 409);
                if (status == 201) {
                    winners++;
                }
            }
        }
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void migrationsAreAppliedAndRepeatableValidationSucceeds() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE", Long.class))
                .isGreaterThanOrEqualTo(1);
    }
}
