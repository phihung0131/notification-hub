package org.example.tenantservice.kafka.consumer;

import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.events.NotificationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TestKafka {

    private final Tracer tracer;

    @KafkaListener(topics = "notification-topic")
    public void listen(NotificationEvent eventDto) {
        try {
            log.debug("Event {}", eventDto); // logging với {} sẽ gọi toString() nếu eventDto != null
            if (eventDto == null) {
                log.warn("Received null NotificationEvent");
            }

            if (tracer != null) {
                var span = tracer.currentSpan();
                log.debug("Tracer bean is present {}", tracer.toString());
                if (span != null && span.context() != null) {
                    log.debug("Trace id: {}", span.context().traceId());
                    log.debug("SpanId: {}", span.context().spanId());
                } else {
                    log.debug("No current span available");
                }
            } else {
                log.debug("Tracer bean is null");
            }
        } catch (Exception e) {
            log.error("Exception in listener — payload will be logged for inspection", e);
            // rethrow để DefaultErrorHandler tiếp tục hành vi hiện tại (hoặc decide to swallow)
            throw e;
        }
    }

}
