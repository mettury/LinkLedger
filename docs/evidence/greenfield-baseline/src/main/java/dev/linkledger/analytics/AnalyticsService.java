package dev.linkledger.analytics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnalyticsService {
    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsService.class);
    private final AnalyticsRepository repository;
    private final TransactionTemplate transaction;
    private final Counter failures;
    private final Counter recorded;

    public AnalyticsService(AnalyticsRepository repository, PlatformTransactionManager manager, MeterRegistry registry) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(1);
        failures = registry.counter("linkledger.analytics.failures");
        recorded = registry.counter("linkledger.analytics.recorded");
    }

    public void record(String code, Instant time) {
        try {
            transaction.executeWithoutResult(status -> repository.increment(code, time));
            recorded.increment();
        } catch (DataAccessException | TransactionException failure) {
            failures.increment();
            // Do not log URLs, API keys, IP addresses or database exception messages.
            LOG.warn("event=analytics_write_failed category={}", failure.getClass().getSimpleName());
        }
    }

    public AnalyticsResponse get(String code) {
        return repository.find(code);
    }
}
