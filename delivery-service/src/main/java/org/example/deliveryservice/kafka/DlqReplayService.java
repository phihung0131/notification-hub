package org.example.deliveryservice.kafka;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.kafka.producer.KafkaProducerService;
import org.example.deliveryservice.state.DeliveryStateRepository;
import org.example.events.NotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Replays dead-lettered notifications to their original topic. Loop guard: each replay bumps {@code
 * x-replay-count}; records at the limit are skipped and stay in the DLQ.
 */
@Service
@Slf4j
public class DlqReplayService {

    public record Result(int replayed, int skippedLoopGuard, int skippedUnreadable) {}

    private static final String GROUP = "delivery-dlq-replay";
    private static final Object LOCK = new Object();

    private final ConsumerFactory<Object, Object> consumerFactory;
    private final KafkaProducerService producer;
    private final DeliveryStateRepository state;
    private final DeliveryProperties props;
    private final String dlqTopic;
    private final String defaultTopic;

    public DlqReplayService(
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaProducerService producer,
            DeliveryStateRepository state,
            DeliveryProperties props,
            @Value("${app.kafka.topic.dlq}") String dlqTopic,
            @Value("${app.kafka.topic.requested}") String defaultTopic) {
        this.consumerFactory = consumerFactory;
        this.producer = producer;
        this.state = state;
        this.props = props;
        this.dlqTopic = dlqTopic;
        this.defaultTopic = defaultTopic;
    }

    /** Replay up to {@code limit} DLQ records (offsets are committed only after republish). */
    public Result replay(int limit) {
        int max = Math.min(Math.max(limit, 1), props.getReplay().getMaxBatch());
        int replayed = 0;
        int guarded = 0;
        int unreadable = 0;
        synchronized (LOCK) { // one replay at a time
            try (Consumer<Object, Object> consumer =
                    consumerFactory.createConsumer(GROUP, "replay", null)) {
                consumer.subscribe(List.of(dlqTopic));
                int handled = 0;
                int emptyPolls = 0;
                while (handled < max && emptyPolls < 2) {
                    ConsumerRecords<Object, Object> batch =
                            consumer.poll(props.getReplay().getPollTimeout());
                    if (batch.isEmpty()) {
                        emptyPolls++;
                        continue;
                    }
                    emptyPolls = 0;
                    for (ConsumerRecord<Object, Object> r : batch) {
                        switch (replayOne(r)) {
                            case REPLAYED -> replayed++;
                            case GUARDED -> guarded++;
                            default -> unreadable++;
                        }
                        handled++;
                    }
                    consumer.commitSync();
                }
            }
        }
        log.info(
                "DLQ replay: replayed={} loopGuard={} unreadable={}",
                replayed,
                guarded,
                unreadable);
        return new Result(replayed, guarded, unreadable);
    }

    private enum Outcome {
        REPLAYED,
        GUARDED,
        UNREADABLE
    }

    private Outcome replayOne(ConsumerRecord<Object, Object> r) {
        if (!(r.value() instanceof NotificationEvent event) || event.getId() == null) {
            return Outcome.UNREADABLE;
        }
        int count = headerInt(r, RetryRouter.H_REPLAY_COUNT);
        if (count >= props.getReplay().getMaxReplays()) {
            return Outcome.GUARDED;
        }
        Header orig = r.headers().lastHeader(RetryRouter.H_ORIGINAL_TOPIC);
        String target =
                orig == null ? defaultTopic : new String(orig.value(), StandardCharsets.UTF_8);
        if (target.equals(dlqTopic)) {
            target = defaultTopic; // never replay into the DLQ itself
        }
        UUID id = event.getId();
        state.resetFailed(id);

        RecordHeaders headers = new RecordHeaders();
        Header prio = r.headers().lastHeader("priority");
        if (prio != null) {
            headers.add(prio);
        }
        headers.add(
                RetryRouter.H_REPLAY_COUNT,
                String.valueOf(count + 1).getBytes(StandardCharsets.UTF_8));
        // attempt counter intentionally absent: replay starts again from tier 0
        producer.send(new ProducerRecord<>(target, null, id.toString(), event, headers));
        return Outcome.REPLAYED;
    }

    private static int headerInt(ConsumerRecord<?, ?> r, String name) {
        Header h = r.headers().lastHeader(name);
        if (h == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
