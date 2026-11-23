package com.deviceagent.adb;

import java.util.Arrays;

/** Little helpers for endianness and concatenation. */
public final class Bytes {
    private Bytes() {}

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static void putU32LE(byte[] buf, int off, int v) {
        buf[off] = (byte) v;
        buf[off + 1] = (byte) (v >>> 8);
        buf[off + 2] = (byte) (v >>> 16);
        buf[off + 3] = (byte) (v >>> 24);
    }

    public static int getU32LE(byte[] buf, int off) {
        return (buf[off] & 0xff)
                | ((buf[off + 1] & 0xff) << 8)
                | ((buf[off + 2] & 0xff) << 16)
                | ((buf[off + 3] & 0xff) << 24);
    }

    public static void putU32BE(byte[] buf, int off, int v) {
        buf[off] = (byte) (v >>> 24);
        buf[off + 1] = (byte) (v >>> 16);
        buf[off + 2] = (byte) (v >>> 8);
        buf[off + 3] = (byte) v;
    }

    public static int getU32BE(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 24)
                | ((buf[off + 1] & 0xff) << 16)
                | ((buf[off + 2] & 0xff) << 8)
                | (buf[off + 3] & 0xff);
    }

    public static void putU64BE(byte[] buf, int off, long v) {
        buf[off] = (byte) (v >>> 56);
        buf[off + 1] = (byte) (v >>> 48);
        buf[off + 2] = (byte) (v >>> 40);
        buf[off + 3] = (byte) (v >>> 32);
        buf[off + 4] = (byte) (v >>> 24);
        buf[off + 5] = (byte) (v >>> 16);
        buf[off + 6] = (byte) (v >>> 8);
        buf[off + 7] = (byte) v;
    }

    public static long getU64BE(byte[] buf, int off) {
        return ((long) (buf[off] & 0xff) << 56)
                | ((long) (buf[off + 1] & 0xff) << 48)
                | ((long) (buf[off + 2] & 0xff) << 40)
                | ((long) (buf[off + 3] & 0xff) << 32)
                | ((long) (buf[off + 4] & 0xff) << 24)
                | ((long) (buf[off + 5] & 0xff) << 16)
                | ((long) (buf[off + 6] & 0xff) << 8)
                | (long) (buf[off + 7] & 0xff);
    }

    public static byte[] fromHex(String hex) {
        int n = hex.length();
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xf, 16));
            sb.append(Character.forDigit(v & 0xf, 16));
        }
        return sb.toString();
    }

    static void readFully(java.io.InputStream in, byte[] buf, int off, int len)
            throws java.io.IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) {
                throw new java.io.EOFException("expected " + len + " bytes, got " + got);
            }
            got += n;
        }
    }
}
