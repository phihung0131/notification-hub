package org.example.notificationservice.service;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.example.events.NotificationEvent;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.common.exception.BaseException;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.example.notificationservice.grpc.client.TenantServiceGrpcClient;
import org.example.notificationservice.kafka.producer.KafkaProducerService;
import org.example.notificationservice.mapper.NotificationMapper;
import org.example.notificationservice.model.Channel;
import org.example.notificationservice.repository.ChannelRepository;
import org.example.notificationservice.repository.NotificationRepository;
import org.example.proto.tenant.GetTenantQuotaResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final ChannelRepository channelRepository;
    private final TenantServiceGrpcClient tenantServiceGrpcClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final KafkaProducerService kafkaProducerService;

    private final Integer QUOTA_QUANTITY_DEFAULT = 100; // Default quota if not set

    public SendNotificationResponse sendNotification(SendNotificationRequest request) {
        if (getTenantQuota(request.getTenantId()) <= 0) {
            throw new BaseException(ApiErrorMessage.QUOTA_EXCEEDED);
        }

        // reduce quota
        redisTemplate.opsForValue().decrement(getCacheKeyForTenantQuota(request.getTenantId()));

        NotificationEvent eventDto = NotificationMapper.toEventDto(request);
        kafkaProducerService.sendMessage(eventDto);

        // build and return response
        SendNotificationResponse response = new SendNotificationResponse();
        response.setId(eventDto.getId().toString());
        response.setTenantId(eventDto.getTenantId());
        response.setNotificationStatus(NotificationStatus.PENDING);

        return null;
    }

    public Integer getTenantQuota(@NotBlank String tenantId) {
        String cacheKey = getCacheKeyForTenantQuota(tenantId);
        Integer quotaInRedis = (Integer) redisTemplate.opsForValue().get(cacheKey);
        if (quotaInRedis != null && quotaInRedis > 0) return quotaInRedis;

        // Not in cache or zero, fetch from gRPC service
        GetTenantQuotaResponse response = tenantServiceGrpcClient.getTenantQuota(tenantId, QUOTA_QUANTITY_DEFAULT);

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
