package org.example.deliveryservice.provider;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.events.NotificationEvent;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Generic JSON-over-HTTP provider (SMS / push / chat). Works against the mock or a real API. */
public class HttpChannelProvider implements ChannelProvider {

    private final String channel;
    private final RestClient client;
    private final String path;
    private final String apiKey;
    private final Duration defaultRetryAfter;

    public HttpChannelProvider(
            String channel,
            RestClient client,
            DeliveryProperties.Provider props,
            Duration defaultRetryAfter) {
        this.channel = channel;
        this.client = client;
        this.path = props.getPath();
        this.apiKey = props.getApiKey();
        this.defaultRetryAfter = defaultRetryAfter;
    }

    @Override
    public String channel() {
        return channel;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ProviderResult send(NotificationEvent event, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notificationId", idempotencyKey);
        body.put("tenantId", str(event.getTenantId()));
        body.put("channel", channel);
        body.put("to", str(event.getRecipient()));
        body.put("subject", str(event.getSubject()));
        body.put("content", str(event.getContent()));
        try {
            return client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(
                            h -> {
                                h.set("Idempotency-Key", idempotencyKey);
                                if (apiKey != null && !apiKey.isBlank()) {
                                    h.setBearerAuth(apiKey);
                                }
                            })
                    .body(body)
                    .exchange(
                            (req, res) -> {
                                HttpStatusCode code = res.getStatusCode();
                                if (code.is2xxSuccessful()) {
                                    Map<String, Object> json = null;
                                    try {
                                        json = res.bodyTo(Map.class);
                                    } catch (RuntimeException ignored) {
                                        // body is optional
                                    }
                                    String id =
                                            json != null && json.get("id") != null
                                                    ? json.get("id").toString()
                                                    : idempotencyKey;
                                    return new ProviderResult(id, "accepted by " + channel);
                                }
                                if (code.value() == 429) {
                                    Duration ra =
                                            parseRetryAfter(
                                                    res.getHeaders().getFirst("Retry-After"));
                                    throw DeliveryException.rateLimited(
                                            "provider rate limited (429)", ra);
                                }
                                if (code.is5xxServerError() || code.value() == 408) {
                                    throw DeliveryException.retryable(
                                            "provider error " + code.value(), (Throwable) null);
                                }
                                throw DeliveryException.permanent(
                                        "provider rejected request " + code.value(), null);
                            });
        } catch (DeliveryException e) {
            throw e;
        } catch (RestClientException e) {
            throw DeliveryException.retryable("provider I/O failure: " + e.getMessage(), e);
        }
    }

    private Duration parseRetryAfter(String header) {
        if (header != null) {
            try {
                return Duration.ofSeconds(Long.parseLong(header.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return defaultRetryAfter;
    }

    private static String str(CharSequence c) {
        return c == null ? null : c.toString();
    }
}
