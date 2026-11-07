package org.example.deliveryservice.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.config.DeliveryProperties.Retry.Tier;
import org.example.deliveryservice.provider.DeliveryException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.stereotype.Component;

/** Decides where a failed record goes next: a delay tier topic or the DLQ. Pure logic. */
@Component
public class RetryRouter {

    public static final String H_ATTEMPT = "x-attempt";
    public static final String H_REASON = "x-failure-reason";
    public static final String H_CLASS = "x-failure-class";
    public static final String H_ORIGINAL_TOPIC = "x-original-topic";
    public static final String H_FAILED_AT = "x-failed-at";
    public static final String H_REPLAY_COUNT = "x-replay-count";

    /** attempt = number of failures including this one. */
    public record Route(String topic, int attempt, boolean dlq, String reason) {}

    private final List<Tier> tiers;
    private final String dlqTopic;

    public RetryRouter(
            DeliveryProperties props,
            @org.springframework.beans.factory.annotation.Value("${app.kafka.topic.dlq}")
                    String dlqTopic) {
        this.tiers = props.getRetry().getTiers();
        this.dlqTopic = dlqTopic;
    }

    public Route route(int failuresSoFar, Throwable error) {
        String reason = rootMessage(error);
        DeliveryException de = find(error, DeliveryException.class);
        boolean poison = find(error, DeserializationException.class) != null;
        if (poison || (de != null && de.isPermanent())) {
            return new Route(dlqTopic, failuresSoFar + 1, true, reason);
        }
        int idx = failuresSoFar;
        if (de != null && de.retryAfter() != null) {
            idx = Math.max(idx, firstTierCovering(de.retryAfter()));
        }
        if (idx >= tiers.size()) {
            return new Route(dlqTopic, failuresSoFar + 1, true, reason);
        }
        return new Route(tiers.get(idx).getTopic(), idx + 1, false, reason);
    }

    /** Smallest tier whose delay honours retryAfter; the last tier if none is long enough. */
    private int firstTierCovering(Duration retryAfter) {
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).getDelay().compareTo(retryAfter) >= 0) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    public static int attemptOf(Headers headers) {
        Header h = headers.lastHeader(H_ATTEMPT);
        if (h == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static <T extends Throwable> T find(Throwable t, Class<T> type) {
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
        }
        return null;
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        for (int i = 0; root.getCause() != null && i < 10; i++) {
            root = root.getCause();
        }
        DeliveryException de = find(t, DeliveryException.class);
        String m = de != null ? de.getMessage() : root.getMessage();
        return m == null ? root.getClass().getSimpleName() : m;
    }
}
