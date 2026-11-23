package com.deviceagent.crypto;

/**
 * HKDF-SHA256 (RFC 5869) and HMAC-SHA256. Empty salt is HashLen zeros.
 * Delegates to {@link com.deviceagent.adb.Hkdf} so ADB pairing and link
 * pairing share one implementation.
 */
public final class Hkdf {
    private Hkdf() {}

    public static byte[] sha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        return com.deviceagent.adb.Hkdf.sha256(ikm, salt, info, length);
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        return com.deviceagent.adb.Hkdf.hmac(key, data);
    }
}
