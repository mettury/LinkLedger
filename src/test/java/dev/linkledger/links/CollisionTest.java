package dev.linkledger.links;

import dev.linkledger.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:collisions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class CollisionTest {
    @Autowired LinkService links;
    @MockitoBean CodeGenerator generator;

    @Test
    void retriesReservedAndCollidingCandidatesThenSucceeds() {
        links.create(new CreateLinkRequest("https://example.com/existing", "taken123", null), null);
        when(generator.generate()).thenReturn("actuator", "taken123", "fresh123");
        assertThat(links.create(new CreateLinkRequest("https://example.com/new", null, null), null).link().code())
                .isEqualTo("fresh123");
    }

    @Test
    void boundedRetriesReturnServiceUnavailableWithoutOverwritingExistingLink() {
        links.create(new CreateLinkRequest("https://example.com/original", "stuck123", null), null);
        when(generator.generate()).thenReturn("stuck123");
        assertThatThrownBy(() -> links.create(new CreateLinkRequest("https://example.com/not-written", null, null), null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status().value()).isEqualTo(503));
        assertThat(links.find("stuck123").url()).isEqualTo("https://example.com/original");
    }
}
