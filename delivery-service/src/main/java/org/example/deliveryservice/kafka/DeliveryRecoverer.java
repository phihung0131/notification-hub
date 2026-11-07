package org.example.deliveryservice.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.example.deliveryservice.kafka.RetryRouter.Route;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.state.DeliveryState;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.example.events.NotificationEvent;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import lombok.extern.slf4j.Slf4j;

/**
 * Called by the error handler for every failed record. Re-publishes it to the next retry tier or,
 * when exhausted/permanent, emits the FAILED result event and dead-letters it with failure headers.
 */
@Slf4j
public class DeliveryRecoverer implements ConsumerRecordRecoverer {

    private final RetryRouter router;
    private final DeliveryStateRepository state;
    private final KafkaProducerService producer;
    private final DeadLetterPublishingRecoverer publisher;

    public DeliveryRecoverer(
            RetryRouter router,
            DeliveryStateRepository state,
            KafkaProducerService producer,
            KafkaOperations<?, ?> avroTemplate,
            KafkaOperations<?, ?> bytesTemplate) {
        this.router = router;
        this.state = state;
        this.producer = producer;
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate); // poison messages keep raw bytes
        templates.put(Object.class, avroTemplate);
        this.publisher =
                new DeadLetterPublishingRecoverer(
                        templates, (rec, ex) -> new TopicPartition(routeOf(rec, ex).topic(), -1));
        this.publisher.setHeadersFunction(this::headers);
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception ex) {
        Route route = routeOf(record, ex);
        if (route.dlq()
                && record.value() instanceof NotificationEvent event
                && event.getId() != null) {
            // never lose a failure: tell downstream (quota/analytics) before parking the record
            if (state.failIfInProgress(event.getId(), route.reason())) {
                producer.sendResult(
                        NotificationEvent.newBuilder(event)
                                .setStatus(DeliveryState.FAILED.toEventStatus())
                                .build());
                state.markResultPublished(event.getId());
            }
        }
        log.warn(
                "Record {}-{}@{} failed (attempt {}): {} -> {}",
                record.topic(),
                record.partition(),
                record.offset(),
                route.attempt(),
                route.reason(),
                route.topic());
        publisher.accept(record, ex);
    }

    private Route routeOf(ConsumerRecord<?, ?> record, Exception ex) {
        return router.route(RetryRouter.attemptOf(record.headers()), ex);
    }

    private Headers headers(ConsumerRecord<?, ?> record, Exception ex) {
        Route route = routeOf(record, ex);
        Headers h = new RecordHeaders();
        add(h, RetryRouter.H_ATTEMPT, String.valueOf(route.attempt()));
        add(h, RetryRouter.H_REASON, truncate(route.reason(), 500));
        add(h, RetryRouter.H_CLASS, rootClass(ex));
        add(h, RetryRouter.H_FAILED_AT, Instant.now().toString());
        Header original = record.headers().lastHeader(RetryRouter.H_ORIGINAL_TOPIC);
        if (original == null) {
            add(h, RetryRouter.H_ORIGINAL_TOPIC, record.topic());
        }
        return h;
    }

    private static String rootClass(Throwable t) {
        Throwable r = t;
        for (int i = 0; r.getCause() != null && i < 10; i++) {
            r = r.getCause();
        }
        return r.getClass().getName();
    }

    private static void add(Headers h, String k, String v) {
        h.add(k, v.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
