package org.example.notificationservice.config;

import java.time.Clock;

import org.example.notificationservice.application.KafkaFirstWritePath;
import org.example.notificationservice.application.OutboxWritePath;
import org.example.notificationservice.application.WritePath;
import org.example.notificationservice.application.port.EventPublisherPort;
import org.example.notificationservice.application.port.NotificationWritePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Selects the write path from {@code notification.write-mode=outbox|kafka-first}. */
@Configuration
@EnableScheduling
public class WritePathConfig {

    @Bean
    @ConditionalOnProperty(
            name = "notification.write-mode",
            havingValue = "outbox",
            matchIfMissing = true)
    public WritePath outboxWritePath(NotificationWritePort writePort) {
        return new OutboxWritePath(writePort);
    }

    @Bean
    @ConditionalOnProperty(name = "notification.write-mode", havingValue = "kafka-first")
    public WritePath kafkaFirstWritePath(EventPublisherPort publisher) {
        return new KafkaFirstWritePath(publisher);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
