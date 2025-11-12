package org.example.notificationservice.config.logger;

import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka Producer Interceptor - logs outgoing messages
 */
@Slf4j
@Component
public class KafkaProducerLoggingInterceptor implements ProducerInterceptor<String, String> {

    private final Tracer tracer;

    public KafkaProducerLoggingInterceptor(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        var span = tracer.currentSpan();
        String traceId = span != null ? span.context().traceId() : "unknown";

        log.info("[KAFKA SEND] Topic: {} | Partition: {} | Key: {} | TraceId: {}",
                record.topic(),
                record.partition(),
                record.key(),
                traceId);

        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        if (exception != null) {
            log.error("[KAFKA ACK ERROR] Topic: {} | Error: {}",
                    metadata.topic(),
                    exception.getMessage());
        }
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}