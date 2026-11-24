package com.deviceagent.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mutual HMAC authentication between the Backstage Android app and its local
 * uid-2000 daemon on 127.0.0.1. Prevents a malicious local app squatting on
 * the daemon port from harvesting localToken, deviceToken, or link keys.
 */
public final class LocalAuth {
    private static final SecureRandom RNG = new SecureRandom();
    private static final long MAX_SKEW_MS = 120_000L;

    private LocalAuth() {}

    public static String challenge() {
        byte[] bytes = new byte[16];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String daemonProof(String localToken, String challenge) {
        if (localToken == null || localToken.isEmpty() || challenge == null || !challenge.matches("[A-Za-z0-9_-]{16,64}")) {
            return "";
        }
        return hmacB64(localToken, "backstage-daemon-v1\n" + challenge);
    }

    public static boolean verifyDaemonProof(String localToken, String challenge, String proof) {
        if (proof == null || proof.isEmpty()) return false;
        String expected = daemonProof(localToken, challenge);
        if (expected.isEmpty()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                proof.getBytes(StandardCharsets.UTF_8));
    }

    public static String requestHeader(String localToken, String method, String path, String body) {
        return requestHeader(localToken, method, path, body, System.currentTimeMillis());
    }

    public static String requestHeader(String localToken, String method, String path, String body, long nowMs) {
        if (localToken == null || localToken.isEmpty()) return "";
        String nonce = challenge();
        String ts = Long.toString(nowMs);
        String cleanMethod = method == null ? "GET" : method.toUpperCase(Locale.US);
        String cleanPath = stripQuery(path);
        String payload = "backstage-admin-v1\n" + cleanMethod + "\n" + cleanPath + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body == null ? "" : body);
        return ts + "." + nonce + "." + hmacB64(localToken, payload);
    }

    public static boolean verifyRequestHeader(String localToken, String header, String method, String path, String body, long nowMs) {
        if (localToken == null || localToken.isEmpty() || header == null || header.isBlank()) return false;
        String[] parts = header.trim().split("\\.");
        if (parts.length != 3) return false;
        long ts;
        try {
            ts = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowMs - ts) > MAX_SKEW_MS) return false;
        String nonce = parts[1];
        if (!nonce.matches("[A-Za-z0-9_-]{12,64}")) return false;
        String cleanMethod = method == null ? "GET" : method.toUpperCase(Locale.US);
        String cleanPath = stripQuery(path);
        String payload = "backstage-admin-v1\n" + cleanMethod + "\n" + cleanPath + "\n" + parts[0] + "\n" + nonce + "\n" + sha256Hex(body == null ? "" : body);
        String expected = hmacB64(localToken, payload);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8));
    }

    private static String stripQuery(String path) {
        if (path == null || path.isEmpty()) return "/";
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacB64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
