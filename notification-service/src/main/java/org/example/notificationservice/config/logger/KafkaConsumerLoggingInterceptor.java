package org.example.notificationservice.config.logger;

import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerInterceptor;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka Consumer Interceptor - logs incoming messages
 */
@Slf4j
@Component
class KafkaConsumerLoggingInterceptor implements ConsumerInterceptor<String, String> {

    private final Tracer tracer;

    public KafkaConsumerLoggingInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public ConsumerRecords<String, String> onConsume(ConsumerRecords<String, String> records) {
        records.forEach(record -> {
            var span = tracer.currentSpan();
            String traceId = span != null ? span.context().traceId() : "unknown";

            log.info("[KAFKA RECEIVE] Topic: {} | Partition: {} | Offset: {} | Key: {} | TraceId: {}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    traceId);
        });

        return records;
    }

    @Override
    public void onCommit(Map offsets) {
        log.debug("[KAFKA COMMIT] Offsets committed: {}", offsets);
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}