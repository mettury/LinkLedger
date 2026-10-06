package dev.linkledger.links;

import dev.linkledger.api.ApiException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:aliases;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AliasTest {
    @Autowired LinkService links;

    @Test
    void createsRequestedAliasAndPreservesRandomCreation() {
        String alias = "launch-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(links.create(new CreateLinkRequest("https://example.com/launch", alias, null), null).link().code())
                .isEqualTo(alias);
        assertThat(links.create(new CreateLinkRequest("https://example.com/random", null, null), null).link().code())
                .matches("[A-Za-z0-9]{8}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ab", "bad alias", "bad/alias", "Api", "ACTUATOR", "error", "a234567890123456789012345678901234"})
    void rejectsInvalidAndReservedAliases(String alias) {
        assertThatThrownBy(() -> links.create(new CreateLinkRequest("https://example.com", alias, null), null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status().value()).isEqualTo(400));
    }

    @Test
    void tombstonesCannotBeReusedAndAliasesAreCaseSensitive() {
        String alias = "campaign-" + UUID.randomUUID().toString().substring(0, 8);
        links.create(new CreateLinkRequest("https://example.com/old", alias, null), null);
        links.disable(alias);
        assertThatThrownBy(() -> links.create(new CreateLinkRequest("https://example.com/new", alias, null), null))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).status().value()).isEqualTo(409));
        assertThat(links.create(new CreateLinkRequest("https://example.com/case", alias.toUpperCase(java.util.Locale.ROOT), null), null)
                .link().status()).isEqualTo("ACTIVE");
    }

    @Test
    void concurrentAliasRequestsHaveOneWinner() throws Exception {
        String alias = "race-" + UUID.randomUUID().toString().substring(0, 8);
        List<Callable<Integer>> tasks = IntStream.range(0, 12).mapToObj(i -> (Callable<Integer>) () -> {
            try {
                links.create(new CreateLinkRequest("https://example.com/race/" + i, alias, null), null);
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
}
