package com.deviceagent.crypto;

import com.deviceagent.adb.Bytes;
import com.deviceagent.tools.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Call/result envelope: AES-256-GCM, nonce = 4 zero bytes + uint64 BE {@code n},
 * AAD = UTF-8 of {@code from + ":" + n}. {@code ct} is base64url(ciphertext || 16-byte tag).
 */
public final class Envelope {
    public static final int VERSION = 1;
    private static final int TAG_BITS = 128;
    private static final int NONCE_LEN = 12;

    public final int v;
    public final String from;
    public final String n;
    public final String ct;
    public String r;

    public Envelope(int v, String from, String n, String ct) {
        this.v = v;
        this.from = from;
        this.n = n;
        this.ct = ct;
    }

    public long nLong() {
        return parseN(n);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", v);
        m.put("from", from);
        m.put("n", n);
        m.put("ct", ct);
        if (r != null) m.put("r", r);
        return m;
    }

    public String toJson() {
        return Json.stringify(toMap());
    }

    public static Envelope seal(byte[] key, String from, long n, byte[] plaintext) {
        if (from == null || from.isEmpty()) {
            throw new IllegalArgumentException("envelope from is required");
        }
        if (plaintext == null) plaintext = new byte[0];
        String nStr = Long.toUnsignedString(n);
        byte[] nonce = nonce(n);
        byte[] aad = aad(from, nStr);
        byte[] ct = aesGcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext);
        return new Envelope(VERSION, from, nStr, LinkPairing.b64url(ct));
    }

    private static byte[] v2Key(byte[] key) {
        return Hkdf.sha256(key, new byte[0], "backstage envelope v2".getBytes(StandardCharsets.UTF_8), 32);
    }

    public static Envelope sealRandom(byte[] key, String from, long n, byte[] plaintext) {
        byte[] nonce = new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
        String r = LinkPairing.b64url(nonce), counter = Long.toUnsignedString(n);
        byte[] ct = aesGcm(Cipher.ENCRYPT_MODE, v2Key(key), nonce,
                ("2:" + from + ":" + counter + ":" + r).getBytes(StandardCharsets.UTF_8), plaintext);
        Envelope env = new Envelope(2, from, counter, LinkPairing.b64url(ct)); env.r=r; return env;
    }

    public static byte[] open(byte[] key, Envelope env) {
        if (env == null) throw new SecurityException("missing envelope");
        if (env.v == 2) {
            if (env.r == null || !env.r.matches("[A-Za-z0-9_-]{16}")) throw new SecurityException("bad nonce");
            parseN(env.n);
            return aesGcm(Cipher.DECRYPT_MODE, v2Key(key), LinkPairing.unb64url(env.r),
                    ("2:" + env.from + ":" + env.n + ":" + env.r).getBytes(StandardCharsets.UTF_8),
                    LinkPairing.unb64url(env.ct));
        }
        if (env.v != VERSION) throw new SecurityException("unsupported envelope version");
        if (env.from == null || env.from.isEmpty() || env.n == null || env.ct == null) {
            throw new SecurityException("malformed envelope");
        }
        long n;
        try {
            n = env.nLong();
        } catch (RuntimeException e) {
            throw new SecurityException("malformed envelope");
        }
        byte[] nonce = nonce(n);
        byte[] aad = aad(env.from, env.n);
        byte[] ctAndTag;
        try {
            ctAndTag = LinkPairing.unb64url(env.ct);
        } catch (RuntimeException e) {
            throw new SecurityException("malformed envelope");
        }
        return aesGcm(Cipher.DECRYPT_MODE, key, nonce, aad, ctAndTag);
    }

    public static Envelope parse(Object raw) {
        Map<String, Object> obj;
        if (raw instanceof Envelope) return (Envelope) raw;
        if (raw instanceof String) {
            obj = Json.object((String) raw);
        } else if (raw instanceof Map) {
            obj = (Map<String, Object>) raw;
        } else if (raw == null) {
            throw new IllegalArgumentException("missing envelope");
        } else {
            throw new IllegalArgumentException("envelope must be an object");
        }
        Object vRaw = obj.get("v");
        int v = 0;
        if (vRaw instanceof Number) v = ((Number) vRaw).intValue();
        else if (vRaw != null && !String.valueOf(vRaw).isBlank()) {
            try {
                v = Integer.parseInt(String.valueOf(vRaw).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("envelope v is not a number");
            }
        }
        String from = Json.string(obj, "from");
        String n = nString(obj.get("n"));
        String ct = Json.string(obj, "ct");
        if (from == null || n == null || ct == null) {
            throw new IllegalArgumentException("envelope requires from, n, ct");
        }
        Envelope env = new Envelope(v, from, n, ct);
        env.r = Json.string(obj, "r");
        return env;
    }

    public static byte[] nonce(long n) {
        byte[] nonce = new byte[NONCE_LEN];
        Bytes.putU64BE(nonce, 4, n);
        return nonce;
    }

    public static byte[] aad(String from, String n) {
        return (from + ":" + n).getBytes(StandardCharsets.UTF_8);
    }

    public static long parseN(String n) {
        if (n == null || n.isBlank()) throw new IllegalArgumentException("n is required");
        return Long.parseUnsignedLong(n.trim());
    }

    private static String nString(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String) return ((String) raw).trim();
        if (raw instanceof Number) return Long.toUnsignedString(((Number) raw).longValue());
        return String.valueOf(raw).trim();
    }

    private static byte[] aesGcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("AES-256-GCM key must be 32 bytes");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad);
            return c.doFinal(input);
        } catch (AEADBadTagException e) {
            throw new SecurityException("bad tag", e);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("AES-GCM failed", e);
        }
    }
}
