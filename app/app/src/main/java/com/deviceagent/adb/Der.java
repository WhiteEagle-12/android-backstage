package com.deviceagent.adb;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Minimal DER encoder for a self-signed X.509 v3 certificate (RSA-SHA256).
 * No BouncyCastle: hand-built TBS + {@code SHA256withRSA} signature, then
 * re-parsed with {@code CertificateFactory.getInstance("X.509")}.
 */
final class Der {
    private Der() {}

    static final int TAG_INTEGER = 0x02;
    static final int TAG_BIT_STRING = 0x03;
    static final int TAG_OCTET_STRING = 0x04;
    static final int TAG_NULL = 0x05;
    static final int TAG_OID = 0x06;
    static final int TAG_UTF8 = 0x0c;
    static final int TAG_SEQUENCE = 0x30;
    static final int TAG_SET = 0x31;
    static final int TAG_PRINTABLE = 0x13;
    static final int TAG_UTC_TIME = 0x17;
    static final int TAG_CONTEXT_0_CONSTRUCTED = 0xa0;

    /** sha256WithRSAEncryption 1.2.840.113549.1.1.11 */
    static final byte[] OID_SHA256_RSA = oid(1, 2, 840, 113549, 1, 1, 11);
    /** commonName 2.5.4.3 */
    static final byte[] OID_CN = oid(2, 5, 4, 3);

    static X509Certificate selfSignedRsa(String cn, PublicKey publicKey, PrivateKey privateKey, Date notBefore, Date notAfter)
            throws GeneralSecurityException {
        byte[] spki = publicKey.getEncoded();
        byte[] algId = sequence(OID_SHA256_RSA, derNull());
        byte[] version = tlv(TAG_CONTEXT_0_CONSTRUCTED, integer(BigInteger.valueOf(2)));
        byte[] serial = integer(BigInteger.ONE);
        byte[] name = directoryName(cn);
        byte[] validity = sequence(utcTime(notBefore), utcTime(notAfter));
        byte[] tbs = sequence(version, serial, algId, name, validity, name, spki);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(tbs);
        byte[] signature = sig.sign();

        byte[] certDer = sequence(tbs, algId, bitString(signature));
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(certDer));
    }

    static byte[] directoryName(String cn) {
        byte[] attr = sequence(OID_CN, tlv(TAG_UTF8, cn.getBytes(StandardCharsets.UTF_8)));
        byte[] rdn = tlv(TAG_SET, attr);
        return sequence(rdn);
    }

    static byte[] integer(BigInteger v) {
        byte[] raw = v.toByteArray();
        return tlv(TAG_INTEGER, raw);
    }

    static byte[] bitString(byte[] bits) {
        byte[] body = new byte[bits.length + 1];
        body[0] = 0; // unused bits
        System.arraycopy(bits, 0, body, 1, bits.length);
        return tlv(TAG_BIT_STRING, body);
    }

    static byte[] derNull() {
        return new byte[] {TAG_NULL, 0};
    }

    static byte[] sequence(byte[]... parts) {
        return tlv(TAG_SEQUENCE, concat(parts));
    }

    static byte[] utcTime(Date date) {
        // UTCTime YYMMDDHHMMSSZ — valid for 1950–2049 (our 10-year certs stay in range).
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(date);
        int year = cal.get(java.util.Calendar.YEAR);
        int yy = year % 100;
        String s = String.format(
                java.util.Locale.US,
                "%02d%02d%02d%02d%02d%02dZ",
                yy,
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                cal.get(java.util.Calendar.SECOND));
        return tlv(TAG_UTC_TIME, s.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] oid(int... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(40 * arcs[0] + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            writeBase128(out, arcs[i]);
        }
        return tlv(TAG_OID, out.toByteArray());
    }

    static byte[] tlv(int tag, byte[] value) {
        byte[] len = length(value.length);
        byte[] out = new byte[1 + len.length + value.length];
        out[0] = (byte) tag;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(value, 0, out, 1 + len.length, value.length);
        return out;
    }

    static byte[] length(int n) {
        if (n < 0x80) {
            return new byte[] {(byte) n};
        }
        if (n <= 0xff) {
            return new byte[] {(byte) 0x81, (byte) n};
        }
        if (n <= 0xffff) {
            return new byte[] {(byte) 0x82, (byte) (n >>> 8), (byte) n};
        }
        return new byte[] {(byte) 0x83, (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
    }

    private static void writeBase128(ByteArrayOutputStream out, int value) {
        int n = value;
        int bits = 0;
        int tmp = n;
        do {
            bits += 7;
            tmp >>= 7;
        } while (tmp != 0);
        while (bits > 7) {
            bits -= 7;
            out.write(0x80 | ((n >> bits) & 0x7f));
        }
        out.write(n & 0x7f);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }
}
