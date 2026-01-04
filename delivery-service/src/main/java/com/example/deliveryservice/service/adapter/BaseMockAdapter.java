package com.example.deliveryservice.service.adapter;

import java.util.concurrent.ThreadLocalRandom;

import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;

import com.example.deliveryservice.service.model.DeliveryResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class BaseMockAdapter implements DeliveryAdapter {

    private static final int MIN_DELAY_MS = 50;
    private static final int MAX_DELAY_MS = 500;

    private static final double SUCCESS_RATE = 0.9;

    @Override
    public DeliveryResult deliver(NotificationEvent event) {
        // Simulate provider latency
        int delay = ThreadLocalRandom.current().nextInt(MIN_DELAY_MS, MAX_DELAY_MS + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryResult.failure("Interrupted while sending");
        }

        boolean success = ThreadLocalRandom.current().nextDouble() <= SUCCESS_RATE;
        if (success) {
            log.info(
                    "Mock {} adapter delivered message {} in {}ms",
                    getChannel(),
                    event.getId(),
                    delay);
            return DeliveryResult.success();
        }

        String reason = "Mock " + getChannel() + " provider rejected message";
        log.warn(
                "Mock {} adapter failed message {} after {}ms: {}",
                getChannel(),
                event.getId(),
                delay,
                reason);
        return new DeliveryResult(NotificationStatus.FAILED, reason);
    }
}
