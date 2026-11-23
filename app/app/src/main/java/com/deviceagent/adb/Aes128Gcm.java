package com.deviceagent.adb;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AOSP {@code pairing_auth/aes_128_gcm.cpp}: HKDF-SHA256 → AES-128-GCM,
 * 12-byte IV = 64-bit little-endian counter (enc/dec counters independent)
 * padded with zeros, no AAD, 16-byte tag.
 *
 * HKDF info is {@code "adb pairing_auth aes-128-gcm key"} without a trailing NUL
 * ({@code sizeof(info) - 1} in AOSP).
 */
final class Aes128Gcm {
    static final byte[] HKDF_INFO =
            "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private final SecretKeySpec key;
    private long encSeq;
    private long decSeq;

    Aes128Gcm(byte[] spakeKeyMaterial) {
        byte[] aesKey = Hkdf.sha256(spakeKeyMaterial, new byte[0], HKDF_INFO, 16);
        this.key = new SecretKeySpec(aesKey, "AES");
    }

    byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = ivFor(encSeq++);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    byte[] decrypt(byte[] ciphertextAndTag) {
        try {
            byte[] iv = ivFor(decSeq++);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(ciphertextAndTag);
        } catch (AEADBadTagException e) {
            throw new SecurityException("AES-GCM decrypt failed (tag mismatch / wrong order)", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    static byte[] ivFor(long counter) {
        byte[] iv = new byte[IV_LEN];
        for (int i = 0; i < 8; i++) {
            iv[i] = (byte) (counter >>> (8 * i));
        }
        return iv;
    }

    /** For tests: encrypt/decrypt counters start at 0 and are independent. */
    long encryptCounter() {
        return encSeq;
    }

    long decryptCounter() {
        return decSeq;
    }

    static boolean equal(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
