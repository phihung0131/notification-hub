package org.example.analyticsservice.service;

import java.util.List;
import java.util.UUID;

import org.example.analyticsservice.dto.response.MessageResponse;
import org.example.analyticsservice.model.Message;
import org.example.analyticsservice.repository.MessageRepository;
import org.example.commons.util.MapUtil;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Query service for retrieving message analytics and delivery status. */
@Service
@RequiredArgsConstructor
public class MessageQueryService {

    private final MessageRepository messageRepository;

    /**
     * Retrieves a message by ID.
     *
     * @param id message UUID
     * @return message response or null if not found
     */
    public MessageResponse getById(UUID id) {
        Message m = messageRepository.findById(id).orElse(null);
        return toResponse(m);
    }

    /**
     * Lists all messages for a tenant, ordered by creation time (newest first).
     *
     * @param tenantId tenant ID for filtering
     * @return list of message responses
     */
    public List<MessageResponse> listByTenant(String tenantId) {
        return messageRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Converts Message entity to MessageResponse DTO.
     *
     * @param m message entity (can be null)
     * @return message response or null
     */
    private MessageResponse toResponse(Message m) {
        if (m == null) {
            return null;
        }
        MessageResponse dto = new MessageResponse();
        MapUtil.copyProperties(m, dto);
        return dto;
    }
}
