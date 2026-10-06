package dev.linkledger.links;

import dev.linkledger.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:link-service;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class LinkServiceTest {
    @Autowired LinkService links;
    @MockitoBean Clock clock;

    @Test
    void expiryBoundaryIsGoneAndIdempotentRetryStillWorksAfterExpiry() {
        Instant start = Instant.parse("2030-01-01T00:00:00Z");
        when(clock.instant()).thenReturn(start);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        CreateLinkRequest request = new CreateLinkRequest("https://example.com/expiry", null, start.plusSeconds(1));
        String key = java.util.UUID.randomUUID().toString();
        String code = links.create(request, key).link().code();
        when(clock.instant()).thenReturn(start.plusMillis(999));
        assertThat(links.resolve(code).code()).isEqualTo(code);
        when(clock.instant()).thenReturn(start.plusSeconds(1));
        assertThatThrownBy(() -> links.resolve(code)).isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(410));
        assertThat(links.create(request, key).created()).isFalse();
        assertThat(links.create(request, key).link().status()).isEqualTo("EXPIRED");
    }
}
