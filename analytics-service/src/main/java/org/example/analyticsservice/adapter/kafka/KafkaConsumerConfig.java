package org.example.analyticsservice.adapter.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConsumerConfig {

    /**
     * DB failures must never skip events: retry the batch forever with capped exponential backoff
     * (1s up to 30s). Un-ingestable events (no id/tenant) are filtered inside the ingest service
     * instead of failing the batch, so there is no poison-pill loop.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return new DefaultErrorHandler(backOff);
    }
}
