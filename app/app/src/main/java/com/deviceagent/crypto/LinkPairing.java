package com.deviceagent.crypto;

import com.deviceagent.adb.Spake2;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Device-side (Bob) SPAKE2 link pairing and confirm / link-key derivation.
 *
 * Names include the trailing NUL. Password is the full typed code
 * {@code XXXX-YYYYYY} as ASCII.
 */
public final class LinkPairing {
    public static final byte[] AGENT_NAME = new byte[] {
            'b', 'a', 'c', 'k', 's', 't', 'a', 'g', 'e', ' ', 'a', 'g', 'e', 'n', 't', 0
    };
    public static final byte[] DEVICE_NAME = new byte[] {
            'b', 'a', 'c', 'k', 's', 't', 'a', 'g', 'e', ' ', 'd', 'e', 'v', 'i', 'c', 'e', 0
    };

    public static final byte[] INFO_LINK = "backstage link v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] INFO_CALL = "backstage call v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] INFO_RESULT = "backstage result v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] CONFIRM_AGENT = "confirm agent".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] CONFIRM_DEVICE = "confirm device".getBytes(StandardCharsets.US_ASCII);

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";

    private LinkPairing() {}

    /** Device is Bob: myName = {@code "backstage device\0"}, theirName = {@code "backstage agent\0"}. */
    public static Spake2 bob() {
        return new Spake2(Spake2.Role.BOB, DEVICE_NAME, AGENT_NAME);
    }

    public static Spake2 alice() {
        return new Spake2(Spake2.Role.ALICE, AGENT_NAME, DEVICE_NAME);
    }

    /**
     * Trim, uppercase, strip spaces/dashes, then insert the dash after 4 characters.
     * Result is {@code XXXX-YYYYYY}.
     */
    public static String normalizeCode(String input) {
        if (input == null) throw new IllegalArgumentException("link code is required");
        StringBuilder alnum = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = Character.toUpperCase(input.charAt(i));
            if (c == ' ' || c == '-' || c == '\u2010' || c == '\u2011'
                    || c == '\u2012' || c == '\u2013' || c == '\u2014' || c == '\u2212') {
                continue;
            }
            alnum.append(c);
        }
        String s = alnum.toString();
        if (s.length() != 10) {
            throw new IllegalArgumentException("link code must be XXXX-YYYYYY");
        }
        for (int i = 0; i < s.length(); i++) {
            if (ALPHABET.indexOf(s.charAt(i)) < 0) {
                throw new IllegalArgumentException("link code has an invalid character");
            }
        }
        return s.substring(0, 4) + "-" + s.substring(4);
    }

    public static String sessionId(String normalizedCode) {
        String code = normalizeCode(normalizedCode);
        return code.substring(0, 4);
    }

    public static byte[] passwordBytes(String normalizedCode) {
        return normalizeCode(normalizedCode).getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] linkKey(byte[] spakeKey64) {
        if (spakeKey64 == null || spakeKey64.length != 64) {
            throw new IllegalArgumentException("SPAKE2 key must be 64 bytes");
        }
        return Hkdf.sha256(spakeKey64, new byte[0], INFO_LINK, 32);
    }

    public static byte[] callKey(byte[] linkKey) {
        requireLinkKey(linkKey);
        return Hkdf.sha256(linkKey, new byte[0], INFO_CALL, 32);
    }

    public static byte[] resultKey(byte[] linkKey) {
        requireLinkKey(linkKey);
        return Hkdf.sha256(linkKey, new byte[0], INFO_RESULT, 32);
    }

    public static byte[] confirmA(byte[] linkKey) {
        requireLinkKey(linkKey);
        return Hkdf.hmacSha256(linkKey, CONFIRM_AGENT);
    }

    public static byte[] confirmB(byte[] linkKey) {
        requireLinkKey(linkKey);
        return Hkdf.hmacSha256(linkKey, CONFIRM_DEVICE);
    }

    public static String confirmAB64(byte[] linkKey) {
        return b64url(confirmA(linkKey));
    }

    public static String confirmBB64(byte[] linkKey) {
        return b64url(confirmB(linkKey));
    }

    public static boolean confirmEquals(String expectedB64, String receivedB64) {
        if (expectedB64 == null || receivedB64 == null) return false;
        byte[] a;
        byte[] b;
        try {
            a = unb64url(expectedB64);
            b = unb64url(receivedB64);
        } catch (RuntimeException e) {
            return false;
        }
        return java.security.MessageDigest.isEqual(a, b);
    }

    public static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] unb64url(String s) {
        if (s == null) throw new IllegalArgumentException("missing base64url");
        return Base64.getUrlDecoder().decode(s);
    }

    private static void requireLinkKey(byte[] linkKey) {
        if (linkKey == null || linkKey.length != 32) {
            throw new IllegalArgumentException("linkKey must be 32 bytes");
        }
    }
}
