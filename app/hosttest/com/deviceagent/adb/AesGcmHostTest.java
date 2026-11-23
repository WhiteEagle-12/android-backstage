package com.deviceagent.adb;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host self-test 3: AES-GCM counter IV encrypt 0 then 1; wrong-order decrypt fails. */
public final class AesGcmHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("AesGcmHostTest OK");
    }

    static void run(PrintStream out) throws Exception {
        byte[] km = new byte[64];
        for (int i = 0; i < 64; i++) {
            km[i] = (byte) (i * 3);
        }
        Aes128Gcm enc = new Aes128Gcm(km);
        Aes128Gcm dec = new Aes128Gcm(km);
        byte[] p0 = "hello-0".getBytes(StandardCharsets.US_ASCII);
        byte[] p1 = "hello-1".getBytes(StandardCharsets.US_ASCII);
        byte[] c0 = enc.encrypt(p0);
        byte[] c1 = enc.encrypt(p1);
        check(!Arrays.equals(c0, c1), "ciphertexts equal");
        check(Arrays.equals(dec.decrypt(c0), p0), "decrypt 0");
        check(Arrays.equals(dec.decrypt(c1), p1), "decrypt 1");

        Aes128Gcm dec2 = new Aes128Gcm(km);
        boolean failed = false;
        try {
            dec2.decrypt(c1); // counter 0 expected, ciphertext was under counter 1
        } catch (SecurityException e) {
            failed = true;
        }
        check(failed, "wrong-order decrypt should fail");

        byte[] iv0 = Aes128Gcm.ivFor(0);
        byte[] iv1 = Aes128Gcm.ivFor(1);
        check(iv0.length == 12 && iv0[0] == 0 && iv0[8] == 0, "iv0");
        check(iv1[0] == 1 && iv1[1] == 0, "iv1 little-endian counter");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
