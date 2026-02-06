package org.example.analyticsservice.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.example.analyticsservice.mapper.MessageMapper;
import org.example.analyticsservice.model.Message;
import org.example.analyticsservice.repository.MessageRepository;
import org.example.events.NotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageBatchService {

    private final MessageRepository messageRepository;

    @Value("${app.batch.size:50}")
    private int batchSize;

    @Value("${app.batch.flush-interval-ms:5000}")
    private long flushIntervalMs;

    private final List<NotificationEvent> requestedBuffer = new CopyOnWriteArrayList<>();
    private final List<NotificationEvent> resultBuffer = new CopyOnWriteArrayList<>();
    private final AtomicLong lastFlush = new AtomicLong(System.currentTimeMillis());

    public void handleRequested(NotificationEvent event) {
        requestedBuffer.add(event);
        if (requestedBuffer.size() >= batchSize) {
            flush("requested-batch");
        }
    }

    public void handleResult(NotificationEvent event) {
        resultBuffer.add(event);
        if (resultBuffer.size() >= batchSize) {
            flush("result-batch");
        }
    }

    @Scheduled(fixedDelayString = "${app.batch.flush-interval-ms:5000}")
    public void scheduledFlush() {
        if ((!requestedBuffer.isEmpty() || !resultBuffer.isEmpty())
                && (System.currentTimeMillis() - lastFlush.get()) >= flushIntervalMs) {
            flush("scheduled");
        }
    }

    @Transactional
    public void flush(String reason) {
        List<NotificationEvent> requested;
        List<NotificationEvent> results;
        requested = new ArrayList<>(requestedBuffer);
        results = new ArrayList<>(resultBuffer);
        requestedBuffer.clear();
        resultBuffer.clear();
        lastFlush.set(System.currentTimeMillis());

        if (requested.isEmpty() && results.isEmpty()) {
            return;
        }

        // Group existing messages to update
        Set<UUID> ids = new HashSet<>();
        requested.forEach(
                e -> {
                    if (e.getId() != null) ids.add(UUID.fromString(e.getId().toString()));
                });
        results.forEach(
                e -> {
                    if (e.getId() != null) ids.add(UUID.fromString(e.getId().toString()));
                });

        Map<UUID, Message> existing =
                new HashMap<>(
                        ids.isEmpty()
                                ? Collections.emptyMap()
                                : messageRepository.findAllById(ids).stream()
                                        .collect(Collectors.toMap(Message::getMessageId, m -> m)));

        List<Message> toSave = new ArrayList<>();

        // Process requested events
        for (NotificationEvent e : requested) {
            UUID id = e.getId() != null ? UUID.fromString(e.getId().toString()) : UUID.randomUUID();
            Message msg = existing.getOrDefault(id, MessageMapper.toMessage(e));
            msg.setMessageId(id);
            MessageMapper.merge(msg, e);
            toSave.add(msg);
            existing.put(id, msg);
        }

        // Process result events (may arrive before requested)
        for (NotificationEvent e : results) {
            UUID id = e.getId() != null ? UUID.fromString(e.getId().toString()) : UUID.randomUUID();
            Message msg = existing.getOrDefault(id, MessageMapper.toMessage(e));
            msg.setMessageId(id);
            MessageMapper.merge(msg, e);
            toSave.add(msg);
            existing.put(id, msg);
        }

        messageRepository.saveAll(toSave);
        log.info(
                "Flushed analytics batch: requested={}, result={}, saved={}, reason={}",
                requested.size(),
                results.size(),
                toSave.size(),
                reason);
    }
}
