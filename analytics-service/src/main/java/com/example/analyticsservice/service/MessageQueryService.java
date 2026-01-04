package com.example.analyticsservice.service;

import java.util.List;
import java.util.UUID;

import org.example.commons.util.MapUtil;
import org.springframework.stereotype.Service;

import com.example.analyticsservice.dto.response.MessageResponse;
import com.example.analyticsservice.model.Message;
import com.example.analyticsservice.repository.MessageRepository;

import lombok.RequiredArgsConstructor;

/**
 * Query service for retrieving message analytics and delivery status.
 *
 * <p>Provides read-only access to message history with tenant isolation. Converts Message entities
 * to MessageResponse DTOs.
 *
 * <h2>Responsibilities:</h2>
 *
 * <ul>
 *   <li>Query messages by ID
 *   <li>List messages by tenant (ordered by creation time)
 *   <li>Entity to DTO conversion
 * </ul>
 *
 * <h2>Thread Safety:</h2>
 *
 * <p>Stateless service, safe for concurrent access.
 *
 * @author Notification Hub Team
 * @version 1.0
 * @since 1.0
 * @see MessageRepository
 * @see MessageResponse
 */
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
