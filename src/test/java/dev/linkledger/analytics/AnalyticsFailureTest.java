package dev.linkledger.analytics;

import dev.linkledger.links.CreateLinkRequest;
import dev.linkledger.links.LinkService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:analytics-failure;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsFailureTest {
    @Autowired MockMvc mvc;
    @Autowired LinkService links;
    @Autowired MeterRegistry registry;
    @MockitoSpyBean AnalyticsRepository repository;

    @Test
    void failedAnalyticsTransactionPreservesRedirectAndIncrementsFailureMetric() throws Exception {
        String code = links.create(new CreateLinkRequest("https://example.com/reliable", null, null), null).link().code();
        doThrow(new DataAccessResourceFailureException("simulated storage failure"))
                .when(repository).increment(eq(code), any());
        double before = registry.counter("linkledger.analytics.failures").count();
        mvc.perform(get("/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/reliable"));
        assertThat(registry.counter("linkledger.analytics.failures").count()).isEqualTo(before + 1);
        assertThat(repository.find(code).totalRedirects()).isZero();
    }
}
