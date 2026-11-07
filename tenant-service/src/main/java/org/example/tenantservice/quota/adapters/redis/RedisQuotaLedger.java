package org.example.tenantservice.quota.adapters.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.example.tenantservice.quota.application.port.QuotaLedgerPort;
import org.example.tenantservice.quota.domain.QuotaSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis implementation of the quota ledger. All multi-step operations are Lua scripts so they are
 * atomic on the Redis server. Time comes from Redis (TIME), not from the app host, so hold expiry
 * is consistent across tenant-service instances.
 *
 * <p>Per tenant keys (hash-tagged so a Redis Cluster keeps them in one slot): {@code
 * quota:{t}:limit}, {@code quota:{t}:used}, {@code quota:{t}:resv} (ZSET reservationId to expiry
 * ms).
 */
@Component
@Slf4j
public class RedisQuotaLedger implements QuotaLedgerPort {

    static final String NOW =
            "local t = redis.call('TIME') local now = t[1] * 1000 + math.floor(t[2] / 1000) ";

    static final String RESERVE =
            NOW
                    + "local limit = redis.call('GET', KEYS[1]) "
                    + "local used = redis.call('GET', KEYS[2]) "
                    + "if (not limit) or (not used) then return {-1, 0, 0, 0} end "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now) "
                    + "limit = tonumber(limit) used = tonumber(used) "
                    + "local held = redis.call('ZCARD', KEYS[3]) "
                    + "if redis.call('ZSCORE', KEYS[3], ARGV[1]) then return {1, limit, used, held} end "
                    + "if limit >= 0 and used + held >= limit then return {0, limit, used, held} end "
                    + "redis.call('ZADD', KEYS[3], now + tonumber(ARGV[2]), ARGV[1]) "
                    + "return {1, limit, used, held + 1}";

    static final String INITIALIZE =
            "redis.call('SETNX', KEYS[1], ARGV[1]) redis.call('SETNX', KEYS[2], ARGV[2]) return 1";

    static final String SYNC =
            "redis.call('SET', KEYS[1], ARGV[1]) "
                    + "local cur = tonumber(redis.call('GET', KEYS[2]) or '0') "
                    + "if tonumber(ARGV[2]) > cur or not redis.call('GET', KEYS[2]) then "
                    + "redis.call('SET', KEYS[2], ARGV[2]) end return 1";

    static final String COMMIT =
            "for i = 2, #ARGV do redis.call('ZREM', KEYS[3], ARGV[i]) end "
                    + "local cur = tonumber(redis.call('GET', KEYS[2]) or '0') "
                    + "if tonumber(ARGV[1]) > cur or not redis.call('GET', KEYS[2]) then "
                    + "redis.call('SET', KEYS[2], ARGV[1]) end return 1";

    static final String RELEASE = "return redis.call('ZREM', KEYS[3], ARGV[1])";

    static final String SNAPSHOT =
            NOW
                    + "local limit = redis.call('GET', KEYS[1]) "
                    + "local used = redis.call('GET', KEYS[2]) "
                    + "if (not limit) or (not used) then return {} end "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now) "
                    + "return {tonumber(limit), tonumber(used), redis.call('ZCARD', KEYS[3])}";

    private final StringRedisTemplate redis;

    public RedisQuotaLedger(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static List<String> keys(String tenantId) {
        String p = "quota:{" + tenantId + "}:";
        return List.of(p + "limit", p + "used", p + "resv");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Long> run(String script, String tenantId, Object... args) {
        DefaultRedisScript<List> s = new DefaultRedisScript<>(script, List.class);
        String[] a = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            a[i] = String.valueOf(args[i]);
        }
        return (List<Long>) redis.execute(s, keys(tenantId), (Object[]) a);
    }

    private void runVoid(String script, String tenantId, Object... args) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>(script, Long.class);
        String[] a = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            a[i] = String.valueOf(args[i]);
        }
        redis.execute(s, keys(tenantId), (Object[]) a);
    }

    @Override
    public ReserveReply reserve(String tenantId, String reservationId, Duration ttl) {
        List<Long> r = run(RESERVE, tenantId, reservationId, ttl.toMillis());
        long code = r.get(0);
        if (code < 0) {
            return new ReserveReply(Reservation.NOT_INITIALIZED, null);
        }
        QuotaSnapshot snap =
                new QuotaSnapshot(r.get(1).intValue(), r.get(2).intValue(), r.get(3).intValue());
        return new ReserveReply(code == 1 ? Reservation.GRANTED : Reservation.EXCEEDED, snap);
    }

    @Override
    public void initialize(String tenantId, int limit, int used) {
        runVoid(INITIALIZE, tenantId, limit, used);
    }

    @Override
    public void sync(String tenantId, int limit, int used) {
        runVoid(SYNC, tenantId, limit, used);
    }

    @Override
    public void commit(String tenantId, Collection<String> reservationIds, int durableUsed) {
        List<Object> args = new ArrayList<>();
        args.add(durableUsed);
        args.addAll(reservationIds);
        runVoid(COMMIT, tenantId, args.toArray());
    }

    @Override
    public boolean release(String tenantId, String reservationId) {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>(RELEASE, Long.class);
        Long removed = redis.execute(s, keys(tenantId), reservationId);
        return removed != null && removed > 0;
    }

    @Override
    public Optional<QuotaSnapshot> snapshot(String tenantId) {
        List<Long> r = run(SNAPSHOT, tenantId);
        if (r == null || r.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new QuotaSnapshot(r.get(0).intValue(), r.get(1).intValue(), r.get(2).intValue()));
    }
}
