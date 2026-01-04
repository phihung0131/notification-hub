package com.example.analyticsservice.mapper;

import java.time.Instant;
import java.util.UUID;

import org.example.commons.util.MapUtil;
import org.example.events.NotificationEvent;
import org.example.events.enums.NotificationStatus;

import com.example.analyticsservice.model.Message;

public class MessageMapper {

    public static Message toMessage(NotificationEvent event) {
        Message m = new Message();
        MapUtil.copyProperties(event, m);
        if (event.getId() != null) {
            m.setMessageId(UUID.fromString(event.getId().toString()));
        } else {
            m.setMessageId(UUID.randomUUID());
        }
        m.setStatus(event.getStatus() != null ? event.getStatus() : NotificationStatus.PENDING);
        m.setCreatedAt(toInstant(event.getCreatedAt()));
        m.setUpdatedAt(Instant.now());
        return m;
    }

    public static void merge(Message target, NotificationEvent event) {
        MapUtil.copyProperties(event, target);
        // Keep existing createdAt if present
        if (target.getCreatedAt() == null && event.getCreatedAt() != null) {
            target.setCreatedAt(toInstant(event.getCreatedAt()));
        } else if (target.getCreatedAt() == null) {
            target.setCreatedAt(Instant.now());
        }
        target.setUpdatedAt(Instant.now());
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant inst -> inst;
            case Long l -> Instant.ofEpochMilli(l);
            case null, default -> Instant.now();
        };
    }
}
