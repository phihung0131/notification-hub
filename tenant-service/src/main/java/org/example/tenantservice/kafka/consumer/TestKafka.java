package org.example.tenantservice.kafka.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.events.NotificationEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class TestKafka {

    @KafkaListener(topics = "notification-topic")
    public void listen(NotificationEvent eventDto) {
        try {
            log.debug("Event {}", eventDto); // logging với {} sẽ gọi toString() nếu eventDto != null
            if (eventDto == null) {
                log.warn("Received null NotificationEvent");
            }
        } catch (Exception e) {
            log.error("Exception in listener — payload will be logged for inspection", e);
            throw e;
        }
    }

}
