package org.example.notificationservice.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.example.commons.exception.BaseException;
import org.example.notificationservice.application.port.IdempotencyPort;
import org.example.notificationservice.application.port.IdempotencyPort.Begin;
import org.example.notificationservice.application.port.NotificationWritePort;
import org.example.notificationservice.application.port.NotificationWritePort.Existing;
import org.example.notificationservice.application.port.QuotaPort;
import org.example.notificationservice.application.port.TenantIdempotencyConfigPort;
import org.example.notificationservice.application.port.TenantIdempotencyConfigPort.Config;
import org.example.notificationservice.common.enums.NotificationStatus;
import org.example.notificationservice.common.exception.ApiErrorMessage;
import org.example.notificationservice.domain.DependencyUnavailableException;
import org.example.notificationservice.domain.DuplicateIdempotencyKeyException;
import org.example.notificationservice.domain.IdempotencyMode;
import org.example.notificationservice.domain.NewNotification;
import org.example.notificationservice.domain.RequestFingerprint;
import org.example.notificationservice.dto.request.SendNotificationRequest;
import org.example.notificationservice.dto.response.SendNotificationResponse;
import org.example.notificationservice.service.NotificationValidationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Accept one notification: validate, idempotency claim, exact quota reserve, durable write,
 * respond. Every failure after a side effect compensates (release quota, abort the idempotency
 * claim). Quota or idempotency dependency down means fail closed (503). See ADRs 0003 to 0005.
 */
@Service
@Slf4j
public class SendNotificationUseCase {

    static final String ACCEPTED_MESSAGE =
            "Notification request accepted and queued for processing";
    static final String HASH_PREFIX = "~hash:";

    private final NotificationValidationService validation;
    private final IdempotencyPort idempotency;
    private final QuotaPort quota;
    private final WritePath writePath;
    private final NotificationWritePort writePort;
    private final TenantIdempotencyConfigPort tenantConfig;
    private final Clock clock;
    private final Duration keyTtl;
    private final Duration inFlightTtl;

    public SendNotificationUseCase(
            NotificationValidationService validation,
            IdempotencyPort idempotency,
            QuotaPort quota,
            WritePath writePath,
            NotificationWritePort writePort,
            TenantIdempotencyConfigPort tenantConfig,
            Clock clock,
            @Value("${notification.idempotency.key-ttl:PT24H}") Duration keyTtl,
            @Value("${notification.idempotency.in-flight-ttl:PT60S}") Duration inFlightTtl) {
        this.validation = validation;
        this.idempotency = idempotency;
        this.quota = quota;
        this.writePath = writePath;
        this.writePort = writePort;
        this.tenantConfig = tenantConfig;
        this.clock = clock;
        this.keyTtl = keyTtl;
        this.inFlightTtl = inFlightTtl;
    }

    /** The idempotency key actually used and whether it is persisted in the DB. */
    private record ResolvedKey(String key, boolean persisted, Duration ttl) {}

    public SendResult send(SendCommand cmd) {
        String tenantId = cmd.tenantId();
        SendNotificationRequest req = cmd.request();
        validation.validateRequest(req);

        String priority = normalizePriority(req.getPriority());
        String fingerprint =
                RequestFingerprint.of(
                        req.getChannel(),
                        req.getRecipient(),
                        req.getSubject(),
                        req.getContent(),
                        req.getTemplateId(),
                        req.getVariables(),
                        priority);
        ResolvedKey rk = resolveKey(tenantId, cmd.idempotencyKey(), fingerprint);

        UUID id = UUID.randomUUID();
        String owner = id.toString();
        boolean claimed = false;

        // 1. idempotency claim (Redis fast path)
        if (rk != null) {
            Begin begin = null;
            try {
                begin = idempotency.begin(tenantId, rk.key(), fingerprint, owner, inFlightTtl);
            } catch (RuntimeException e) {
                if (writePath.redisIsSoleIdempotencyArbiter()) {
                    throw new DependencyUnavailableException("idempotency-store", e);
                }
                log.warn("Idempotency store down, DB unique key is the arbiter: {}", e.toString());
            }
            if (begin != null) {
                switch (begin.kind()) {
                    case REPLAY:
                        return replay(tenantId, begin.notificationId());
                    case IN_FLIGHT:
                        throw new BaseException(ApiErrorMessage.IDEMPOTENCY_IN_FLIGHT);
                    case MISMATCH:
                        throw new BaseException(ApiErrorMessage.IDEMPOTENCY_KEY_REUSED);
                    case PROCEED:
                        claimed = true;
                        break;
                }
            }
        }

        // 2. exact quota reserve (atomic, idempotent per notification id)
        QuotaPort.Result reserved;
        try {
            reserved = quota.reserve(tenantId, owner);
        } catch (RuntimeException e) {
            abort(claimed, tenantId, rk, fingerprint, owner);
            throw e;
        }
        if (reserved != QuotaPort.Result.GRANTED) {
            abort(claimed, tenantId, rk, fingerprint, owner);
            throw new BaseException(
                    reserved == QuotaPort.Result.EXCEEDED
                            ? ApiErrorMessage.QUOTA_EXCEEDED
                            : ApiErrorMessage.TENANT_NOT_FOUND);
        }

        // 3. durable write
        NewNotification notification =
                new NewNotification(
                        id,
                        tenantId,
                        req.getChannel(),
                        req.getRecipient(),
                        req.getSubject(),
                        req.getContent(),
                        priority,
                        rk != null && rk.persisted() ? rk.key() : null,
                        fingerprint,
                        clock.instant());
        try {
            writePath.write(notification);
        } catch (DuplicateIdempotencyKeyException dup) {
            // DB unique key is the final arbiter: another request already won
            quota.release(tenantId, owner);
            return resolveLoser(tenantId, rk, fingerprint, owner, claimed);
        } catch (RuntimeException e) {
            quota.release(tenantId, owner);
            abort(claimed, tenantId, rk, fingerprint, owner);
            throw e;
        }

        // 4. remember the response for replays
        if (claimed) {
            try {
                idempotency.complete(tenantId, rk.key(), fingerprint, owner, rk.ttl());
            } catch (RuntimeException e) {
                log.error(
                        "Could not store idempotent response for {}/{}: {}",
                        tenantId,
                        rk.key(),
                        e.toString());
            }
        }
        return new SendResult(response(tenantId, owner), false);
    }

    private SendResult resolveLoser(
            String tenantId, ResolvedKey rk, String fingerprint, String owner, boolean claimed) {
        Existing existing = writePort.findByIdempotencyKey(tenantId, rk.key()).orElse(null);
        if (existing == null) {
            abort(claimed, tenantId, rk, fingerprint, owner);
            throw new IllegalStateException("Duplicate key reported but no row found");
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            abort(claimed, tenantId, rk, fingerprint, owner);
            throw new BaseException(ApiErrorMessage.IDEMPOTENCY_KEY_REUSED);
        }
        if (claimed) {
            try {
                idempotency.complete(tenantId, rk.key(), fingerprint, existing.id(), rk.ttl());
            } catch (RuntimeException e) {
                log.warn("Could not store replay for {}/{}: {}", tenantId, rk.key(), e.toString());
            }
        }
        return replay(tenantId, existing.id());
    }

    private ResolvedKey resolveKey(String tenantId, String header, String fingerprint) {
        if (header != null && !header.isBlank()) {
            return new ResolvedKey(header.trim(), true, keyTtl);
        }
        Config cfg = tenantConfig.forTenant(tenantId);
        if (cfg.mode() == IdempotencyMode.HEADER_FIRST) {
            return new ResolvedKey(HASH_PREFIX + fingerprint, false, cfg.hashWindow());
        }
        return null;
    }

    private void abort(
            boolean claimed, String tenantId, ResolvedKey rk, String fingerprint, String owner) {
        if (!claimed) {
            return;
        }
        try {
            idempotency.abort(tenantId, rk.key(), fingerprint, owner);
        } catch (RuntimeException e) {
            log.warn("Could not abort idempotency claim {}/{}: {}", tenantId, rk.key(), e);
        }
    }

    private static SendResult replay(String tenantId, String notificationId) {
        return new SendResult(response(tenantId, notificationId), true);
    }

    private static SendNotificationResponse response(String tenantId, String id) {
        return SendNotificationResponse.builder()
                .id(id)
                .tenantId(tenantId)
                .notificationStatus(NotificationStatus.PENDING)
                .message(ACCEPTED_MESSAGE)
                .build();
    }

    private static String normalizePriority(String p) {
        if (p == null || p.isBlank()) {
            return "NORMAL";
        }
        String up = p.trim().toUpperCase(Locale.ROOT);
        if (!up.equals("HIGH") && !up.equals("NORMAL") && !up.equals("LOW")) {
            throw new BaseException(ApiErrorMessage.INVALID_PRIORITY);
        }
        return up;
    }
}
