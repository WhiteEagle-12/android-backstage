package com.deviceagent.crypto;

import com.deviceagent.adb.Bytes;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** FileCipher 3 MiB round-trip, tamper, and a BSF1 vector for "hello backstage". */
public final class FileCipherHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("FileCipherHostTest OK");
    }

    public static Map<String, Object> run(PrintStream out) throws Exception {
        byte[] pt = new byte[3 * 1024 * 1024];
        new SecureRandom().nextBytes(pt);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] blob = FileCipher.encrypt(pt, key);
        check(blob.length > 16, "blob header");
        check(blob[0] == 'B' && blob[1] == 'S' && blob[2] == 'F' && blob[3] == '1', "magic");
        byte[] back = FileCipher.decrypt(blob, key);
        check(Arrays.equals(pt, back), "3MiB round trip");

        blob[20] ^= 0x5a;
        boolean tamperFailed = false;
        try {
            FileCipher.decrypt(blob, key);
        } catch (SecurityException e) {
            tamperFailed = true;
        }
        check(tamperFailed, "tamper should fail");

        byte[] hello = "hello backstage".getBytes(StandardCharsets.US_ASCII);
        byte[] fileKey = new byte[32];
        Arrays.fill(fileKey, (byte) 0x11);
        byte[] helloBlob = FileCipher.encrypt(hello, fileKey);
        byte[] helloBack = FileCipher.decrypt(helloBlob, fileKey);
        check(Arrays.equals(hello, helloBack), "hello round trip");

        byte[] zeroLenHeader = Arrays.copyOf(helloBlob, helloBlob.length);
        Arrays.fill(zeroLenHeader, 8, 16, (byte) 0);
        boolean trailingRejected = false;
        try {
            FileCipher.decrypt(zeroLenHeader, fileKey);
        } catch (SecurityException e) {
            trailingRejected = true;
        }
        check(trailingRejected, "forged plaintextLen=0 with trailing ciphertext should fail");
        String blobHex = Bytes.toHex(helloBlob);
        out.println("fileKey=" + Bytes.toHex(fileKey));
        out.println("helloBlobHex=" + blobHex);

        Map<String, Object> vec = new LinkedHashMap<>();
        vec.put("plaintext", "hello backstage");
        vec.put("fileKeyHex", Bytes.toHex(fileKey));
        vec.put("blobHex", blobHex);
        vec.put("chunkSize", FileCipher.CHUNK_SIZE);
        vec.put("aad", "backstage file v1");
        return vec;
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
