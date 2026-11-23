package com.deviceagent.crypto;

import com.deviceagent.adb.Bytes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * BSF1 file encryption: 16-byte header ({@code "BSF1"}, uint32 BE chunkSize,
 * uint64 BE plaintext length) then {@code uint32 BE len(ct_i) || ct_i} per chunk.
 * Each chunk is AES-256-GCM with nonce = 12-byte big-endian chunk index and
 * AAD {@code "backstage file v1"}. {@code ct_i} is ciphertext || 16-byte tag.
 */
public final class FileCipher {
    public static final byte[] MAGIC = new byte[] {'B', 'S', 'F', '1'};
    public static final int CHUNK_SIZE = 1024 * 1024;
    public static final byte[] AAD = "backstage file v1".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_BITS = 128;
    private static final int HEADER_LEN = 16;

    private FileCipher() {}

    public static byte[] encrypt(byte[] plaintext, byte[] fileKey) {
        if (plaintext == null) plaintext = new byte[0];
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(plaintext.length + 64);
            encrypt(new ByteArrayInputStream(plaintext), plaintext.length, out, fileKey);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] decrypt(byte[] blob, byte[] fileKey) {
        if (blob == null) throw new SecurityException("missing ciphertext");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            decrypt(new ByteArrayInputStream(blob), out, fileKey);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void encryptFile(Path plaintext, Path dest, byte[] fileKey) throws IOException {
        long len = Files.size(plaintext);
        try (InputStream in = Files.newInputStream(plaintext);
                OutputStream out = Files.newOutputStream(dest)) {
            encrypt(in, len, out, fileKey);
        }
    }

    public static void decryptFile(Path blob, Path dest, byte[] fileKey) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (InputStream in = Files.newInputStream(blob);
                OutputStream out = Files.newOutputStream(dest)) {
            decrypt(in, out, fileKey);
        }
    }

    public static void encrypt(InputStream in, long plaintextLen, OutputStream out, byte[] fileKey)
            throws IOException {
        requireKey(fileKey);
        if (plaintextLen < 0) throw new IllegalArgumentException("negative length");
        byte[] header = new byte[HEADER_LEN];
        System.arraycopy(MAGIC, 0, header, 0, 4);
        Bytes.putU32BE(header, 4, CHUNK_SIZE);
        Bytes.putU64BE(header, 8, plaintextLen);
        out.write(header);
        long remaining = plaintextLen;
        long index = 0;
        byte[] chunk = new byte[CHUNK_SIZE];
        while (remaining > 0) {
            int want = (int) Math.min(CHUNK_SIZE, remaining);
            int got = readFully(in, chunk, 0, want);
            if (got != want) {
                throw new IOException("short plaintext: expected " + plaintextLen);
            }
            byte[] ct = aesGcm(Cipher.ENCRYPT_MODE, fileKey, nonce(index), Arrays.copyOf(chunk, got));
            writeU32BE(out, ct.length);
            out.write(ct);
            remaining -= got;
            index++;
        }
    }

    public static void decrypt(InputStream in, OutputStream out, byte[] fileKey) throws IOException {
        requireKey(fileKey);
        byte[] header = new byte[HEADER_LEN];
        int n = readFully(in, header, 0, HEADER_LEN);
        if (n != HEADER_LEN) throw new SecurityException("truncated BSF1 header");
        if (header[0] != 'B' || header[1] != 'S' || header[2] != 'F' || header[3] != '1') {
            throw new SecurityException("not a BSF1 blob");
        }
        int chunkSize = Bytes.getU32BE(header, 4);
        if (chunkSize <= 0 || chunkSize > 16 * 1024 * 1024) {
            throw new SecurityException("invalid BSF1 chunkSize");
        }
        long plaintextLen = Bytes.getU64BE(header, 8);
        if (plaintextLen < 0) throw new SecurityException("invalid BSF1 length");
        long remaining = plaintextLen;
        long index = 0;
        while (remaining > 0) {
            int ctLen = readU32BE(in);
            if (ctLen < 16 || ctLen > chunkSize + 16 + 32) {
                throw new SecurityException("invalid BSF1 chunk length");
            }
            byte[] ct = new byte[ctLen];
            if (readFully(in, ct, 0, ctLen) != ctLen) {
                throw new SecurityException("truncated BSF1 chunk");
            }
            byte[] pt;
            try {
                pt = aesGcm(Cipher.DECRYPT_MODE, fileKey, nonce(index), ct);
            } catch (SecurityException e) {
                throw e;
            }
            int want = (int) Math.min(chunkSize, remaining);
            if (pt.length != want) {
                throw new SecurityException("BSF1 chunk plaintext length mismatch");
            }
            out.write(pt);
            remaining -= pt.length;
            index++;
        }
    }

    public static void verifySha256(Path plaintext, String expectedHex) throws IOException {
        if (expectedHex == null || expectedHex.isBlank()) {
            throw new IllegalArgumentException("sha256 is required");
        }
        String got = sha256Hex(plaintext);
        if (!got.equals(expectedHex.trim().toLowerCase(Locale.US))) {
            throw new SecurityException("sha256 mismatch: expected " + expectedHex.trim().toLowerCase(Locale.US)
                    + " got " + got);
        }
    }

    public static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) >= 0) {
                    md.update(buf, 0, r);
                }
            }
            return Bytes.toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] nonce(long chunkIndex) {
        byte[] nonce = new byte[12];
        Bytes.putU64BE(nonce, 4, chunkIndex);
        return nonce;
    }

    private static byte[] aesGcm(int mode, byte[] key, byte[] nonce, byte[] input) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(AAD);
            return c.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new SecurityException("bad tag", e);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("AES-GCM failed", e);
        }
    }

    private static void requireKey(byte[] fileKey) {
        if (fileKey == null || fileKey.length != 32) {
            throw new IllegalArgumentException("fileKey must be 32 bytes");
        }
    }

    private static void writeU32BE(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static int readU32BE(InputStream in) throws IOException {
        byte[] b = new byte[4];
        if (readFully(in, b, 0, 4) != 4) throw new SecurityException("truncated BSF1 chunk length");
        return Bytes.getU32BE(b, 0);
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int r = in.read(buf, off + got, len - got);
            if (r < 0) return got;
            got += r;
        }
        return got;
    }
}
