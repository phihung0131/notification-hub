package org.example.deliveryservice.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** All delivery tunables. Provider URLs/credentials live here so a real provider can drop in. */
@Component("deliveryProperties")
@ConfigurationProperties(prefix = "app.delivery")
@Getter
@Setter
public class DeliveryProperties {

    private Retry retry = new Retry();
    private Lanes lanes = new Lanes();
    private Resilience resilience = new Resilience();
    private Guard guard = new Guard();
    private Webhook webhook = new Webhook();
    private Replay replay = new Replay();
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /** Topic name list for the retry listener (SpEL). */
    public List<String> retryTopicNames() {
        return retry.getTiers().stream().map(Retry.Tier::getTopic).toList();
    }

    @Getter
    @Setter
    public static class Retry {
        /** Ordered delay tiers; attempt N goes to tier N. Past the last tier -> DLQ. */
        private List<Tier> tiers =
                new ArrayList<>(
                        List.of(
                                tier("notification.requested.retry.5s", Duration.ofSeconds(5)),
                                tier("notification.requested.retry.30s", Duration.ofSeconds(30)),
                                tier("notification.requested.retry.2m", Duration.ofMinutes(2))));

        /** Concurrency of the retry listener (<= partitions of retry topics). */
        private int concurrency = 2;

        public Duration delayFor(String topic) {
            return tiers.stream()
                    .filter(t -> t.getTopic().equals(topic))
                    .map(Tier::getDelay)
                    .findFirst()
                    .orElse(Duration.ZERO);
        }

        public static Tier tier(String topic, Duration delay) {
            Tier t = new Tier();
            t.setTopic(topic);
            t.setDelay(delay);
            return t;
        }

        @Getter
        @Setter
        public static class Tier {
            private String topic;
            private Duration delay;
        }
    }

    /** Weighted consumption: thread share and poll size per priority lane. */
    @Getter
    @Setter
    public static class Lanes {
        private Lane high = new Lane(6, 50);
        private Lane normal = new Lane(4, 50);
        private Lane low = new Lane(2, 20);

        @Getter
        @Setter
        public static class Lane {
            private int concurrency;
            private int maxPollRecords;

            public Lane() {}

            public Lane(int concurrency, int maxPollRecords) {
                this.concurrency = concurrency;
                this.maxPollRecords = maxPollRecords;
            }
        }
    }

    @Getter
    @Setter
    public static class Resilience {
        private int maxConcurrentPerChannelLane = 8;
        private Duration bulkheadMaxWait = Duration.ofMillis(100);
        private float failureRateThreshold = 50f;
        private int slidingWindowSize = 20;
        private int minimumCalls = 10;
        private Duration openStateWait = Duration.ofSeconds(15);
        private int halfOpenCalls = 3;
        private Duration defaultRateLimitRetryAfter = Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class Guard {
        private boolean enabled = true;

        /** Max sends per recipient within the window. */
        private int maxPerWindow = 5;

        private Duration window = Duration.ofMinutes(1);

        /** Identical content to same recipient suppressed within this window. */
        private Duration dedupWindow = Duration.ofMinutes(5);
    }

    @Getter
    @Setter
    public static class Webhook {
        private String secret = "change-me";
    }

    @Getter
    @Setter
    public static class Replay {
        /** Max times a DLQ record may be replayed (loop guard). */
        private int maxReplays = 1;

        private int maxBatch = 500;
        private Duration pollTimeout = Duration.ofSeconds(2);
    }

    @Getter
    @Setter
    public static class Provider {
        /** "http" or "email". */
        private String type = "http";

        private String baseUrl;
        private String path = "/v1/send";
        private String apiKey;
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(3);

        /** Optional failover channel used when this provider fails or its circuit is open. */
        private String failoverChannel;

        private String from = "noreply@notification-hub.local";
    }
}
