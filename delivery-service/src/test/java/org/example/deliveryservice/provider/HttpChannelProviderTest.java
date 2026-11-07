package org.example.deliveryservice.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.UUID;

import org.example.deliveryservice.config.DeliveryProperties;
import org.example.deliveryservice.provider.ChannelProvider.ProviderResult;
import org.example.events.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("HttpChannelProvider status handling")
class HttpChannelProviderTest {

    private MockRestServiceServer server;
    private HttpChannelProvider provider;
    private NotificationEvent event;
    private final String key = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        RestClient.Builder b = RestClient.builder().baseUrl("http://provider");
        server = MockRestServiceServer.bindTo(b).build();
        DeliveryProperties.Provider cfg = new DeliveryProperties.Provider();
        cfg.setPath("/v1/sms/send");
        cfg.setApiKey("k");
        provider = new HttpChannelProvider("sms", b.build(), cfg, Duration.ofSeconds(30));
        event =
                NotificationEvent.newBuilder()
                        .setId(UUID.fromString(key))
                        .setTenantId("t")
                        .setChannel("sms")
                        .setRecipient("+84")
                        .setContent("hi")
                        .build();
    }

    @Test
    void successSendsIdempotencyKeyAndBearer() {
        server.expect(requestTo("http://provider/v1/sms/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", key))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer k"))
                .andRespond(withSuccess("{\"id\":\"prov-9\"}", MediaType.APPLICATION_JSON));

        ProviderResult r = provider.send(event, key);

        assertEquals("prov-9", r.providerMessageId());
        server.verify();
    }

    @Test
    @DisplayName("429 honours Retry-After header")
    void rateLimited() {
        server.expect(requestTo("http://provider/v1/sms/send"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "17"));

        DeliveryException e =
                assertThrows(DeliveryException.class, () -> provider.send(event, key));

        assertEquals(DeliveryException.Kind.RATE_LIMITED, e.kind());
        assertEquals(Duration.ofSeconds(17), e.retryAfter());
    }

    @Test
    @DisplayName("429 without header falls back to configured default")
    void rateLimitedDefault() {
        server.expect(requestTo("http://provider/v1/sms/send"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        DeliveryException e =
                assertThrows(DeliveryException.class, () -> provider.send(event, key));

        assertEquals(Duration.ofSeconds(30), e.retryAfter());
    }

    @Test
    void serverErrorIsRetryable() {
        server.expect(requestTo("http://provider/v1/sms/send"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        DeliveryException e =
                assertThrows(DeliveryException.class, () -> provider.send(event, key));

        assertEquals(DeliveryException.Kind.RETRYABLE, e.kind());
    }

    @Test
    void clientErrorIsPermanent() {
        server.expect(requestTo("http://provider/v1/sms/send"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        DeliveryException e =
                assertThrows(DeliveryException.class, () -> provider.send(event, key));

        assertEquals(DeliveryException.Kind.PERMANENT, e.kind());
    }
}
