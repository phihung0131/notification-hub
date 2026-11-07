package org.example.deliveryservice.kafka.lane;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

/** Priority lane. Taken from the optional {@code priority} record header, default NORMAL. */
public enum Lane {
    HIGH,
    NORMAL,
    LOW;

    public static final String HEADER = "priority";

    public static Lane fromHeaders(Headers headers) {
        Header h = headers == null ? null : headers.lastHeader(HEADER);
        if (h == null || h.value() == null) {
            return NORMAL;
        }
        return switch (new String(h.value(), StandardCharsets.UTF_8).trim().toLowerCase()) {
            case "high", "urgent", "otp" -> HIGH;
            case "low", "bulk" -> LOW;
            default -> NORMAL;
        };
    }

    public String label() {
        return name().toLowerCase();
    }
}
