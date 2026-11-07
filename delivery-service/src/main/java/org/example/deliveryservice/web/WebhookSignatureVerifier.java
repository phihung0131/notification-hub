package org.example.deliveryservice.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.example.deliveryservice.config.DeliveryProperties;
import org.springframework.stereotype.Component;

/** HMAC-SHA256 (hex) over the raw body; header may be prefixed with {@code sha256=}. */
@Component
public class WebhookSignatureVerifier {

    private final byte[] secret;

    public WebhookSignatureVerifier(DeliveryProperties props) {
        this.secret = props.getWebhook().getSecret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean verify(String rawBody, String signatureHeader) {
        if (signatureHeader == null || rawBody == null) {
            return false;
        }
        String given =
                signatureHeader.startsWith("sha256=")
                        ? signatureHeader.substring(7)
                        : signatureHeader;
        return MessageDigest.isEqual(
                sign(rawBody).getBytes(StandardCharsets.UTF_8),
                given.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
