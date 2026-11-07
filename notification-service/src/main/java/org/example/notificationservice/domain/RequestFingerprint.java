package org.example.notificationservice.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Canonical SHA-256 of the client-controlled request payload. */
public final class RequestFingerprint {

    private RequestFingerprint() {}

    public static String of(
            String channel,
            String recipient,
            String subject,
            String content,
            String templateId,
            Map<String, String> variables,
            String priority) {
        StringBuilder sb = new StringBuilder();
        field(sb, channel == null ? null : channel.toLowerCase());
        field(sb, recipient);
        field(sb, subject);
        field(sb, content);
        field(sb, templateId);
        field(sb, priority);
        if (variables != null) {
            new TreeMap<>(variables).forEach((k, v) -> field(sb, k + "=" + v));
        }
        return sha256(sb.toString());
    }

    private static void field(StringBuilder sb, String v) {
        // length-prefix avoids ambiguity between ("ab","c") and ("a","bc")
        sb.append(v == null ? -1 : v.length()).append(':').append(v == null ? "" : v).append('|');
    }

    public static String sha256(String s) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
