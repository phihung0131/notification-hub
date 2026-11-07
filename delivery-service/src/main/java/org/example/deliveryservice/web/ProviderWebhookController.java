package org.example.deliveryservice.web;

import java.util.Map;
import java.util.UUID;

import org.example.deliveryservice.service.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Provider status callbacks: {@code {eventId, notificationId, type: sent|delivered|bounced}}. */
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
@Slf4j
public class ProviderWebhookController {

    private final WebhookSignatureVerifier verifier;
    private final WebhookService service;
    private final ObjectMapper mapper;

    @PostMapping("/provider")
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody String raw,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        if (!verifier.verify(raw, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "bad signature"));
        }
        JsonNode json;
        UUID notificationId;
        try {
            json = mapper.readTree(raw);
            notificationId = UUID.fromString(json.path("notificationId").asText());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid payload"));
        }
        String eventId = json.path("eventId").asText("");
        var target = WebhookService.mapType(json.path("type").asText(null));
        if (eventId.isBlank() || target.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "eventId/type required"));
        }
        var outcome = service.apply(eventId, notificationId, target.get());
        // 404 for unknown ids so a real provider retries once our record exists
        HttpStatus status =
                outcome == WebhookService.Outcome.UNKNOWN_NOTIFICATION
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.OK;
        return ResponseEntity.status(status).body(Map.of("outcome", outcome.name()));
    }
}
