package com.deviceagent.adb;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * BoringSSL SPAKE2 over Ed25519 ({@code crypto/curve25519/spake25519.cc}).
 *
 * Alice (client) sends T = x·B + w·M; Bob (server) sends S = y·B + w·N.
 * Shared point K = x·(S − w·N) = y·(T − w·M). Transcript is SHA-512 over
 * length-prefixed (uint64 LE) names, messages, K, and the password SHA-512.
 *
 * ADB uses {@code SPAKE2_CTX_new} defaults: the password-scalar hack is ON
 * (no {@code left_shift_3} on w; bottom 3 bits cleared by adding k·L).
 * Names are C strings including the trailing NUL ({@code sizeof("adb pair client")}).
 */
public final class Spake2 {
    public static final byte[] CLIENT_NAME = new byte[] {
            'a', 'd', 'b', ' ', 'p', 'a', 'i', 'r', ' ', 'c', 'l', 'i', 'e', 'n', 't', 0
    };
    public static final byte[] SERVER_NAME = new byte[] {
            'a', 'd', 'b', ' ', 'p', 'a', 'i', 'r', ' ', 's', 'e', 'r', 'v', 'e', 'r', 0
    };

    public enum Role {
        ALICE,
        BOB
    }

    private final Role role;
    private final byte[] myName;
    private final byte[] theirName;
    private final boolean passwordScalarHack;
    private byte[] privateKey;
    private byte[] passwordScalar;
    private byte[] passwordHash;
    private byte[] myMsg;
    private boolean msgGenerated;

    public Spake2(Role role, byte[] myName, byte[] theirName) {
        this(role, myName, theirName, true);
    }

    /** {@code passwordScalarHack} matches BoringSSL {@code !disable_password_scalar_hack}. */
    public Spake2(Role role, byte[] myName, byte[] theirName, boolean passwordScalarHack) {
        this.role = role;
        this.myName = Arrays.copyOf(myName, myName.length);
        this.theirName = Arrays.copyOf(theirName, theirName.length);
        this.passwordScalarHack = passwordScalarHack;
    }

    public static Spake2 adbClient() {
        return new Spake2(Role.ALICE, CLIENT_NAME, SERVER_NAME);
    }

    public static Spake2 adbServer() {
        return new Spake2(Role.BOB, SERVER_NAME, CLIENT_NAME);
    }

    public byte[] generateMsg(byte[] password) {
        byte[] rnd = new byte[64];
        new SecureRandom().nextBytes(rnd);
        return generateMsg(password, rnd);
    }

    /**
     * @param privateRandom64 64 random bytes that are {@code sc_reduce}d then {@code left_shift_3}d
     *                        to form the ephemeral scalar (BoringSSL {@code RAND_bytes} + reduce).
     */
    public byte[] generateMsg(byte[] password, byte[] privateRandom64) {
        if (msgGenerated) {
            throw new IllegalStateException("generateMsg already called");
        }
        byte[] reduced = Ed25519.scReduce(privateRandom64);
        this.privateKey = Ed25519.leftShift3(reduced);
        Ed25519.Point p = Ed25519.scalarmult(privateKey, Ed25519.B);

        this.passwordHash = sha512(password);
        this.passwordScalar = derivePasswordScalar(passwordHash, passwordScalarHack);

        Ed25519.Point maskBase = role == Role.ALICE ? Ed25519.M : Ed25519.N;
        Ed25519.Point mask = Ed25519.scalarmult(passwordScalar, maskBase);
        Ed25519.Point pStar = Ed25519.add(p, mask);
        this.myMsg = Ed25519.encode(pStar);
        this.msgGenerated = true;
        return Arrays.copyOf(myMsg, myMsg.length);
    }

    public byte[] processMsg(byte[] theirMsg) {
        if (!msgGenerated) {
            throw new IllegalStateException("generateMsg not called");
        }
        if (theirMsg == null || theirMsg.length != 32) {
            throw new IllegalArgumentException("SPAKE2 message must be 32 bytes");
        }
        Ed25519.Point qStar = Ed25519.decode(theirMsg);
        Ed25519.Point peersMaskBase = role == Role.ALICE ? Ed25519.N : Ed25519.M;
        Ed25519.Point peersMask = Ed25519.scalarmult(passwordScalar, peersMaskBase);
        Ed25519.Point q = Ed25519.sub(qStar, peersMask);
        Ed25519.Point k = Ed25519.scalarmult(privateKey, q);
        byte[] kEnc = Ed25519.encode(k);

        MessageDigest sha = sha512Digest();
        if (role == Role.ALICE) {
            updateLen(sha, myName);
            updateLen(sha, theirName);
            updateLen(sha, myMsg);
            updateLen(sha, theirMsg);
        } else {
            updateLen(sha, theirName);
            updateLen(sha, myName);
            updateLen(sha, theirMsg);
            updateLen(sha, myMsg);
        }
        updateLen(sha, kEnc);
        updateLen(sha, passwordHash);
        return sha.digest();
    }

    public byte[] myMsg() {
        return myMsg == null ? null : Arrays.copyOf(myMsg, myMsg.length);
    }

    /**
     * Password scalar: SHA-512 → {@code x25519_sc_reduce}, then (by default) add L, 2L, 4L as
     * needed so the low 3 bits are zero. Matches BoringSSL's password-scalar hack. The result
     * is a 256-bit integer, <strong>not</strong> reduced mod L (reducing would undo the hack).
     */
    static byte[] derivePasswordScalar(byte[] passwordHash64, boolean hack) {
        byte[] reduced = Ed25519.scReduce(passwordHash64);
        if (!hack) {
            return reduced;
        }
        BigInteger s = Ed25519.fromLe(reduced);
        BigInteger order = Ed25519.L;
        if (s.testBit(0)) {
            s = s.add(order);
        }
        order = order.shiftLeft(1);
        if (s.testBit(1)) {
            s = s.add(order);
        }
        order = order.shiftLeft(1);
        if (s.testBit(2)) {
            s = s.add(order);
        }
        return Ed25519.toLe32(s);
    }

    private static void updateLen(MessageDigest sha, byte[] data) {
        long len = data.length;
        byte[] lenLe = new byte[8];
        for (int i = 0; i < 8; i++) {
            lenLe[i] = (byte) (len & 0xff);
            len >>= 8;
        }
        sha.update(lenLe);
        sha.update(data);
    }

    static byte[] sha512(byte[] data) {
        return sha512Digest().digest(data);
    }

    private static MessageDigest sha512Digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
