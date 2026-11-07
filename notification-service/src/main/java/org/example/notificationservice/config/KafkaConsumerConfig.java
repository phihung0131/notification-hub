package org.example.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** Batch listeners with manual ack: offsets are committed only after the DB write succeeded. */
@Configuration
public class KafkaConsumerConfig {

    private ConcurrentKafkaListenerContainerFactory<String, Object> base(
            ConsumerFactory<String, Object> cf, FixedBackOff backOff) {
        ConcurrentKafkaListenerContainerFactory<String, Object> f =
                new ConcurrentKafkaListenerContainerFactory<>();
        f.setConsumerFactory(cf);
        f.setBatchListener(true);
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        f.setCommonErrorHandler(new DefaultErrorHandler(backOff));
        return f;
    }

    /**
     * Status updates: bounded retries (a result can reference a notification that never existed
     * here); after that the batch is logged and skipped.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> resultListenerFactory(
            ConsumerFactory<String, Object> cf) {
        return base(cf, new FixedBackOff(500L, 20L));
    }

    /** Row creation in kafka-first mode must never be skipped: retry until the DB recovers. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> writerListenerFactory(
            ConsumerFactory<String, Object> cf) {
        return base(cf, new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
