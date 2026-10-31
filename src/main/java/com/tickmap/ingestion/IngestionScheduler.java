package com.tickmap.ingestion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

// Off in tests (tickmap.ingestion.enabled=false) so they never call the real API.
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "tickmap.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class IngestionScheduler {

    private final IngestionService ingestion;

    public IngestionScheduler(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    // fixedDelay rather than fixedRate: the next run is timed from when the last one finished.
    @Scheduled(initialDelayString = "${tickmap.ingestion.initial-delay}",
            fixedDelayString = "${tickmap.ingestion.refresh-interval}")
    void sync() {
        ingestion.runSync(IngestionRun.Trigger.SCHEDULED);
    }
}
