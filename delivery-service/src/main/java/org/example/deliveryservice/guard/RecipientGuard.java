package org.example.deliveryservice.guard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.events.NotificationEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Per-recipient sliding-window throttle and content dedup (Redis). Fails open: a Redis outage must
 * not stop delivery.
 */
@Component
@Slf4j
public class RecipientGuard {

    public enum Verdict {
        ALLOW,
        DUPLICATE,
        THROTTLED
    }

    public record Decision(Verdict verdict, Duration retryAfter) {
        static Decision allow() {
            return new Decision(Verdict.ALLOW, null);
        }
    }

    private static final DefaultRedisScript<Long> WINDOW =
            new DefaultRedisScript<>(
                    """
                    local key, now, window, limit, member = KEYS[1], tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), ARGV[4]
                    redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
                    if redis.call('ZSCORE', key, member) then return 0 end
                    if redis.call('ZCARD', key) >= limit then
                      local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                      return math.max(1, tonumber(oldest[2]) + window - now)
                    end
                    redis.call('ZADD', key, now, member)
                    redis.call('PEXPIRE', key, window)
                    return 0
                    """,
                    Long.class);

    private final StringRedisTemplate redis;
    private final DeliveryProperties.Guard cfg;

    public RecipientGuard(StringRedisTemplate redis, DeliveryProperties props) {
        this.redis = redis;
        this.cfg = props.getGuard();
    }

    public Decision check(NotificationEvent event, String notificationId) {
        if (!cfg.isEnabled() || event.getRecipient() == null) {
            return Decision.allow();
        }
        try {
            String scope =
                    event.getTenantId() + ":" + event.getChannel() + ":" + event.getRecipient();
            String dedupKey = "delivery:dedup:" + scope + ":" + contentHash(event);
            Boolean fresh =
                    redis.opsForValue().setIfAbsent(dedupKey, notificationId, cfg.getDedupWindow());
            if (Boolean.FALSE.equals(fresh)
                    && !notificationId.equals(redis.opsForValue().get(dedupKey))) {
                return new Decision(Verdict.DUPLICATE, null);
            }
            Long waitMs =
                    redis.execute(
                            WINDOW,
                            List.of("delivery:throttle:" + scope),
                            String.valueOf(System.currentTimeMillis()),
                            String.valueOf(cfg.getWindow().toMillis()),
                            String.valueOf(cfg.getMaxPerWindow()),
                            notificationId);
            if (waitMs != null && waitMs > 0) {
                return new Decision(Verdict.THROTTLED, Duration.ofMillis(waitMs));
            }
            return Decision.allow();
        } catch (RuntimeException e) {
            log.warn("Recipient guard unavailable, failing open: {}", e.toString());
            return Decision.allow();
        }
    }

    static String contentHash(NotificationEvent e) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String raw = String.valueOf(e.getSubject()) + '\n' + e.getContent();
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
