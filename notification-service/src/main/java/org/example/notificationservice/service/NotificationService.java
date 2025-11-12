package org.example.notificationservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.common.exception.BaseException;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.example.notificationservice.grpc.client.TenantServiceGrpcClient;
import org.example.notificationservice.kafka.dto.NotificationEventDto;
import org.example.notificationservice.kafka.producer.KafkaProducerService;
import org.example.notificationservice.mapper.NotificationMapper;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.model.Notification;
import org.example.notificationservice.repository.ChannelRepository;
import org.example.notificationservice.repository.NotificationRepository;
import org.example.notificationservice.util.MapUtil;
import org.example.proto.tenant.GetTenantQuotaResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final ChannelRepository channelRepository;
    private final TenantServiceGrpcClient tenantServiceGrpcClient;
    private final RedisTemplate<String, Object> redisTemplate;

    // injected for publishing events
    private final KafkaProducerService kafkaProducerService;
    private final ObjectMapper objectMapper;

    public SendNotificationResponse sendNotification(SendNotificationRequest request) {
        if (getTenantQuota(request.getTenantId()) <= 0) {
            throw new BaseException(ApiErrorMessage.QUOTA_EXCEEDED);
        }

        Channel channel = getChannel(request.getChannel());

        // TODO: get template content => render content

        // reduce quota
        redisTemplate.opsForValue().decrement(getCacheKeyForTenantQuota(request.getTenantId()));

        // save notification record with PENDING status
        Notification notification = NotificationMapper.toEntity(request);
        notification.setChannel(channel);
        notification.setStatus(NotificationStatus.PENDING);
        notification = notificationRepository.save(notification);

        // publish Kafka event for notification (serialized as JSON)
        NotificationEventDto event = new NotificationEventDto();
        MapUtil.copyProperties(notification, event);
        event.setNotificationId(notification.getId().toString());
        event.setChannelCode(channel.getName());
        event.setCreatedAt(Instant.now());

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaProducerService.sendMessage(payload);
        } catch (JsonProcessingException e) {
            // If serialization fails, log and continue (don't fail the request). Optionally handle retry.
            // Use standard output here to avoid adding a logger in this small change.
            System.err.println("Failed to serialize notification event: " + e.getMessage());
        }

        // build and return response
        SendNotificationResponse response = new SendNotificationResponse();
        response.setNotificationId(notification.getId().toString());
        response.setTenantId(notification.getTenantId());
        response.setNotificationStatus(notification.getStatus());

        return response;
    }

    public Integer getTenantQuota(@NotBlank String tenantId) {
        String cacheKey = getCacheKeyForTenantQuota(tenantId);
        Integer quotaInRedis = (Integer) redisTemplate.opsForValue().get(cacheKey);
        if (quotaInRedis != null && quotaInRedis > 0) return quotaInRedis;

        // Not in cache or zero, fetch from gRPC service
        GetTenantQuotaResponse response = tenantServiceGrpcClient.getTenantQuota(tenantId, 1); // TODO: not hardcode

        redisTemplate.opsForValue().set(cacheKey, response.getQuotaQuantity());

        return response.getQuotaQuantity();
    }

    public String getCacheKeyForTenantQuota(String tenantId) {
        return "tenant:quota:" + tenantId;
    }

    // TODO: Cache channel info
    public Channel getChannel(String code) {
        return channelRepository.findByCode(code)
                .orElseThrow(() -> new BaseException(ApiErrorMessage.CHANNEL_NOT_FOUND));
    }
}
