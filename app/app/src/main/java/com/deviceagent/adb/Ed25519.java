package com.deviceagent.adb;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Affine twisted-Edwards Ed25519 over GF(2^255-19). Encoding per RFC 8032.
 * Performance is irrelevant (used once per pairing). Package-private.
 *
 * Constants from RFC 8032 and BoringSSL {@code crypto/curve25519/spake25519.cc}.
 */
public final class Ed25519 {
    /** p = 2^255 - 19 */
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    /** L = 2^252 + 27742317777372353535851937790883648493 */
    static final BigInteger L = new BigInteger(
            "7237005577332262213973186563042994240857116359379907606001950938285454250989");

    /** d = -121665/121666 mod p */
    static final BigInteger D = new BigInteger(
            "37095705934669439343138083508754565189542113879843219016388785533085940283555");

    /** sqrt(-1) mod p = 2^((p-1)/4) */
    static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE);

    /** RFC 8032 base point B (encoded 0x5866…66). */
    static final Point B = decode(new byte[] {
            0x58, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
            0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
            0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66,
            0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66, 0x66
    });

    /**
     * BoringSSL SPAKE2 M, encoded:
     * {@code 5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e}
     */
    static final byte[] M_ENCODED = Bytes.fromHex(
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");

    /**
     * BoringSSL SPAKE2 N, encoded:
     * {@code 10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778}
     */
    static final byte[] N_ENCODED = Bytes.fromHex(
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");

    static final Point M = decode(M_ENCODED);
    static final Point N = decode(N_ENCODED);

    private Ed25519() {}

    static final class Point {
        final BigInteger x;
        final BigInteger y;

        Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }
    }

    static Point add(Point p1, Point p2) {
        BigInteger x1 = p1.x;
        BigInteger y1 = p1.y;
        BigInteger x2 = p2.x;
        BigInteger y2 = p2.y;
        BigInteger x1x2 = x1.multiply(x2).mod(P);
        BigInteger y1y2 = y1.multiply(y2).mod(P);
        BigInteger x1y2 = x1.multiply(y2).mod(P);
        BigInteger y1x2 = y1.multiply(x2).mod(P);
        BigInteger dxxyy = D.multiply(x1x2).multiply(y1y2).mod(P);
        BigInteger x3 = x1y2.add(y1x2).multiply(BigInteger.ONE.add(dxxyy).modInverse(P)).mod(P);
        BigInteger y3 = y1y2.add(x1x2).multiply(BigInteger.ONE.subtract(dxxyy).modInverse(P)).mod(P);
        return new Point(x3, y3);
    }

    static Point neg(Point p) {
        return new Point(P.subtract(p.x).mod(P), p.y);
    }

    static Point sub(Point p, Point q) {
        return add(p, neg(q));
    }

    /** Scalar multiplication using the 256-bit little-endian integer (not reduced mod L). */
    static Point scalarmult(byte[] scalar32, Point p) {
        if (scalar32.length != 32) {
            throw new IllegalArgumentException("scalar must be 32 bytes");
        }
        Point r = IDENTITY;
        Point q = p;
        for (int i = 0; i < 32; i++) {
            int b = scalar32[i] & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                if ((b & 1) != 0) {
                    r = add(r, q);
                }
                q = add(q, q);
                b >>= 1;
            }
        }
        return r;
    }

    static Point scalarmult(BigInteger s, Point p) {
        byte[] le = new byte[32];
        BigInteger t = s;
        for (int i = 0; i < 32; i++) {
            le[i] = t.and(BigInteger.valueOf(0xff)).byteValue();
            t = t.shiftRight(8);
        }
        return scalarmult(le, p);
    }

    static byte[] encode(Point pt) {
        byte[] out = toLe32(pt.y);
        if (pt.x.testBit(0)) {
            out[31] = (byte) (out[31] | 0x80);
        }
        return out;
    }

    static Point decode(byte[] enc) {
        if (enc.length != 32) {
            throw new IllegalArgumentException("point encoding must be 32 bytes");
        }
        byte[] copy = Arrays.copyOf(enc, 32);
        int xSign = (copy[31] & 0x80) >>> 7;
        copy[31] = (byte) (copy[31] & 0x7f);
        BigInteger y = fromLe(copy);
        if (y.compareTo(P) >= 0) {
            throw new IllegalArgumentException("y out of range");
        }
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        // x = uv^3 (uv^7)^((p-5)/8)
        BigInteger uv3 = u.multiply(v.modPow(BigInteger.valueOf(3), P)).mod(P);
        BigInteger uv7 = u.multiply(v.modPow(BigInteger.valueOf(7), P)).mod(P);
        BigInteger x = uv3.multiply(uv7.modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
        BigInteger vx2 = v.multiply(x.multiply(x)).mod(P);
        if (!vx2.subtract(u).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(SQRT_M1).mod(P);
            vx2 = v.multiply(x.multiply(x)).mod(P);
            if (!vx2.subtract(u).mod(P).equals(BigInteger.ZERO)) {
                throw new IllegalArgumentException("point not on curve");
            }
        }
        if (x.equals(BigInteger.ZERO) && xSign == 1) {
            throw new IllegalArgumentException("invalid encoding of x=0");
        }
        if (x.testBit(0) != (xSign == 1)) {
            x = P.subtract(x);
        }
        return new Point(x, y);
    }

    /**
     * BoringSSL {@code x25519_sc_reduce}: 64-byte little-endian integer reduced mod L,
     * written as 32 little-endian bytes.
     */
    static byte[] scReduce(byte[] sixtyFour) {
        if (sixtyFour.length < 32) {
            throw new IllegalArgumentException("sc_reduce input too short");
        }
        byte[] in = sixtyFour.length == 64 ? sixtyFour : Arrays.copyOf(sixtyFour, 64);
        BigInteger n = fromLe(in);
        return toLe32(n.mod(L));
    }

    /**
     * BoringSSL {@code left_shift_3}: n := n*8 as a 32-byte little-endian integer
     * (carry out of byte 31 is dropped).
     */
    static byte[] leftShift3(byte[] n32) {
        byte[] n = Arrays.copyOf(n32, 32);
        int carry = 0;
        for (int i = 0; i < 32; i++) {
            int cur = n[i] & 0xff;
            int nextCarry = cur >>> 5;
            n[i] = (byte) ((cur << 3) | carry);
            carry = nextCarry;
        }
        return n;
    }

    static byte[] toLe32(BigInteger v) {
        byte[] out = new byte[32];
        BigInteger t = v;
        for (int i = 0; i < 32; i++) {
            out[i] = t.and(BigInteger.valueOf(0xff)).byteValue();
            t = t.shiftRight(8);
        }
        return out;
    }

    static BigInteger fromLe(byte[] le) {
        byte[] be = new byte[le.length + 1];
        for (int i = 0; i < le.length; i++) {
            be[le.length - i] = le[i];
        }
        return new BigInteger(be);
    }
}
