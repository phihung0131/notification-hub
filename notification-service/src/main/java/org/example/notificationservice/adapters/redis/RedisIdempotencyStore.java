package org.example.notificationservice.adapters.redis;

import java.time.Duration;
import java.util.List;

import org.example.notificationservice.application.port.IdempotencyPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis fast path for idempotency. One key per (tenant, Idempotency-Key):
 *
 * <pre>
 *   idem:{tenant}:{key} = P|fingerprint|owner          in flight (short TTL)
 *   idem:{tenant}:{key} = D|fingerprint|notificationId done (24h TTL), replayable
 * </pre>
 *
 * Claiming is a single {@code SET NX PX}, so exactly one concurrent caller proceeds.
 */
@Component
public class RedisIdempotencyStore implements IdempotencyPort {

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('DEL', KEYS[1]) else return 0 end",
                    Long.class);

    private final StringRedisTemplate redis;

    public RedisIdempotencyStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String redisKey(String tenantId, String key) {
        return "idem:{" + tenantId + "}:" + key;
    }

    @Override
    public Begin begin(
            String tenantId, String key, String fingerprint, String owner, Duration inFlightTtl) {
        String k = redisKey(tenantId, key);
        String mine = "P|" + fingerprint + "|" + owner;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(k, mine, inFlightTtl))) {
                return new Begin(Kind.PROCEED, null);
            }
            String current = redis.opsForValue().get(k);
            if (current == null) {
                continue; // expired between SET NX and GET: try to claim again
            }
            String[] p = current.split("\\|", 3);
            if (p.length < 3 || !p[1].equals(fingerprint)) {
                return new Begin(Kind.MISMATCH, null);
            }
            return "D".equals(p[0])
                    ? new Begin(Kind.REPLAY, p[2])
                    : new Begin(Kind.IN_FLIGHT, null);
        }
        return new Begin(Kind.IN_FLIGHT, null);
    }

    @Override
    public void complete(
            String tenantId, String key, String fingerprint, String notificationId, Duration ttl) {
        redis.opsForValue()
                .set(redisKey(tenantId, key), "D|" + fingerprint + "|" + notificationId, ttl);
    }

    @Override
    public void abort(String tenantId, String key, String fingerprint, String owner) {
        redis.execute(
                COMPARE_AND_DELETE,
                List.of(redisKey(tenantId, key)),
                "P|" + fingerprint + "|" + owner);
    }
}
