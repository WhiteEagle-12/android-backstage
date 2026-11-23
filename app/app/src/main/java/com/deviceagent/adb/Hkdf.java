package com.deviceagent.adb;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF-SHA256 (RFC 5869). Empty salt is HashLen zeros, matching OpenSSL
 * {@code HKDF(..., nullptr, 0, ...)} used by AOSP {@code aes_128_gcm.cpp}.
 */
public final class Hkdf {
    private static final int HASH_LEN = 32;

    private Hkdf() {}

    public static byte[] sha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] s = (salt == null || salt.length == 0) ? new byte[HASH_LEN] : salt;
        byte[] prk = hmac(s, ikm);
        byte[] okm = new byte[length];
        byte[] prev = new byte[0];
        byte[] infoBytes = info == null ? new byte[0] : info;
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            byte[] input = new byte[prev.length + infoBytes.length + 1];
            System.arraycopy(prev, 0, input, 0, prev.length);
            System.arraycopy(infoBytes, 0, input, prev.length, infoBytes.length);
            input[input.length - 1] = (byte) counter;
            prev = hmac(prk, input);
            int n = Math.min(HASH_LEN, length - offset);
            System.arraycopy(prev, 0, okm, offset, n);
            offset += n;
            counter++;
        }
        return okm;
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
