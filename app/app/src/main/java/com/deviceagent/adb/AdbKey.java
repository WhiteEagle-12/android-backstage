package com.deviceagent.adb;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * RSA-2048 ADB identity: {@code adbkey} (PKCS#8 PEM), {@code adbkey.pub} (Android pubkey line),
 * {@code adbcert.der} (self-signed X.509).
 *
 * Pubkey struct from AOSP {@code libcrypto_utils/android_pubkey.c}:
 * {@code { uint32 len=64; uint32 n0inv; uint32 n[64]; uint32 rr[64]; uint32 exponent; }}
 * little-endian words, {@code n0inv = -n^{-1} mod 2^32}, {@code rr = 2^{4096} mod n}.
 */
public final class AdbKey {
    static final int ANDROID_PUBKEY_MODULUS_SIZE = 256;
    static final int ANDROID_PUBKEY_MODULUS_WORDS = 64;
    static final int ANDROID_PUBKEY_ENCODED_SIZE = 4 + 4 + 256 + 256 + 4; // 524

    private static final String KEY_FILE = "adbkey";
    private static final String PUB_FILE = "adbkey.pub";
    private static final String CERT_FILE = "adbcert.der";

    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final String name;

    private AdbKey(KeyPair keyPair, X509Certificate certificate, String name) {
        this.keyPair = keyPair;
        this.certificate = certificate;
        this.name = name;
    }

    /** Loads or creates an RSA-2048 key + self-signed cert under dir (adbkey, adbkey.pub, adbcert.der). */
    public static AdbKey loadOrCreate(File dir, String name) throws IOException {
        if (name == null || name.isEmpty()) {
            name = "backstage";
        }
        Files.createDirectories(dir.toPath());
        File keyFile = new File(dir, KEY_FILE);
        File pubFile = new File(dir, PUB_FILE);
        File certFile = new File(dir, CERT_FILE);
        try {
            if (keyFile.isFile() && certFile.isFile()) {
                byte[] pkcs8 = readPkcs8(keyFile);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
                RSAPublicKey pub = (RSAPublicKey)
                        kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
                KeyPair pair = new KeyPair(pub, priv);
                X509Certificate cert = (X509Certificate)
                        CertificateFactory.getInstance("X.509")
                                .generateCertificate(Files.newInputStream(certFile.toPath()));
                AdbKey loaded = new AdbKey(pair, cert, name);
                if (!pubFile.isFile()) {
                    Files.write(
                            pubFile.toPath(),
                            (loaded.publicKeyLine() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                return loaded;
            }
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), new SecureRandom());
            KeyPair pair = kpg.generateKeyPair();
            Date notBefore = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1));
            Date notAfter = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650));
            X509Certificate cert = Der.selfSignedRsa(name, pair.getPublic(), pair.getPrivate(), notBefore, notAfter);
            AdbKey created = new AdbKey(pair, cert, name);
            Files.write(keyFile.toPath(), pemEncode("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.write(
                    pubFile.toPath(),
                    (created.publicKeyLine() + "\n").getBytes(StandardCharsets.UTF_8));
            Files.write(certFile.toPath(), cert.getEncoded());
            return created;
        } catch (GeneralSecurityException e) {
            throw new IOException("failed to load/create ADB key", e);
        }
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** ADB public-key line: base64(android_pubkey struct) + " " + name. */
    public String publicKeyLine() {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        byte[] encoded = encodeAndroidPubkey(pub);
        String b64 = Base64.getEncoder().encodeToString(encoded);
        return b64 + " " + name;
    }

    /** SSLContext with this key as the only client cert and a trust-all manager (adbd uses self-signed certs). */
    public SSLContext sslContext() throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(
                new KeyManager[] {TlsSupport.rsaKeyManager(keyPair.getPrivate(), certificate)},
                new TrustManager[] {TlsSupport.trustAll()},
                new SecureRandom());
        return ctx;
    }

    static byte[] encodeAndroidPubkey(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.remainder(r32);
        BigInteger n0inv = n0.modInverse(r32).negate(); // ≡ -n^{-1} (mod 2^32); low 32 bits used
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);

        ByteBuffer buf = ByteBuffer.allocate(ANDROID_PUBKEY_ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(ANDROID_PUBKEY_MODULUS_WORDS);
        buf.putInt(n0inv.intValue());
        putLeWords(buf, n);
        putLeWords(buf, rr);
        buf.putInt(e.intValue());
        return buf.array();
    }

    static BigInteger modulusFromPublicKeyLine(String line) {
        String b64 = line.trim();
        int sp = b64.indexOf(' ');
        if (sp >= 0) {
            b64 = b64.substring(0, sp);
        }
        byte[] raw = Base64.getDecoder().decode(b64);
        if (raw.length < ANDROID_PUBKEY_ENCODED_SIZE) {
            throw new IllegalArgumentException("android_pubkey too short: " + raw.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int words = buf.getInt();
        if (words != ANDROID_PUBKEY_MODULUS_WORDS) {
            throw new IllegalArgumentException("unexpected modulus_size_words=" + words);
        }
        buf.getInt(); // n0inv
        return getLeWords(buf, ANDROID_PUBKEY_MODULUS_WORDS);
    }

    static int n0invFromPublicKeyLine(String line) {
        byte[] raw = rawStruct(line);
        return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
    }

    static BigInteger rrFromPublicKeyLine(String line) {
        ByteBuffer buf = ByteBuffer.wrap(rawStruct(line)).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8 + ANDROID_PUBKEY_MODULUS_SIZE);
        return getLeWords(buf, ANDROID_PUBKEY_MODULUS_WORDS);
    }

    private static byte[] rawStruct(String line) {
        String b64 = line.trim();
        int sp = b64.indexOf(' ');
        if (sp >= 0) {
            b64 = b64.substring(0, sp);
        }
        return Base64.getDecoder().decode(b64);
    }

    private static void putLeWords(ByteBuffer buf, BigInteger v) {
        BigInteger tmp = v;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        for (int i = 0; i < ANDROID_PUBKEY_MODULUS_WORDS; i++) {
            BigInteger[] dr = tmp.divideAndRemainder(r32);
            buf.putInt(dr[1].intValue());
            tmp = dr[0];
        }
    }

    private static BigInteger getLeWords(ByteBuffer buf, int words) {
        BigInteger n = BigInteger.ZERO;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger place = BigInteger.ONE;
        for (int i = 0; i < words; i++) {
            long w = buf.getInt() & 0xffffffffL;
            n = n.add(BigInteger.valueOf(w).multiply(place));
            place = place.multiply(r32);
        }
        return n;
    }

    private static byte[] readPkcs8(File keyFile) throws IOException {
        byte[] raw = Files.readAllBytes(keyFile.toPath());
        String text = new String(raw, StandardCharsets.US_ASCII);
        if (text.contains("BEGIN")) {
            return pemDecode(text);
        }
        return raw;
    }

    static byte[] pemEncode(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
        return pem.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] pemDecode(String pem) {
        StringBuilder b64 = new StringBuilder();
        for (String line : pem.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("-----")) {
                continue;
            }
            b64.append(line);
        }
        return Base64.getDecoder().decode(b64.toString());
    }
}
