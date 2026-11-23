package com.deviceagent.adb;

import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import javax.security.auth.x500.X500Principal;

/** Host self-test 1: AdbKey load/reload, android_pubkey n0inv/rr, X.509 parse. */
public final class AdbKeyHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("AdbKeyHostTest OK");
    }

    static void run(PrintStream out) throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("adbkey-test");
        try {
            AdbKey a = AdbKey.loadOrCreate(dir.toFile(), "backstage-test");
            AdbKey b = AdbKey.loadOrCreate(dir.toFile(), "backstage-test");
            BigInteger n1 = ((RSAPublicKey) a.keyPair().getPublic()).getModulus();
            BigInteger n2 = ((RSAPublicKey) b.keyPair().getPublic()).getModulus();
            check(n1.equals(n2), "reload modulus mismatch");

            String line = a.publicKeyLine();
            check(line.contains(" backstage-test"), "pubkey line missing name: " + line);
            BigInteger nFromLine = AdbKey.modulusFromPublicKeyLine(line);
            check(n1.equals(nFromLine), "decoded modulus != key modulus");

            BigInteger r32 = BigInteger.ONE.shiftLeft(32);
            long n0inv = AdbKey.n0invFromPublicKeyLine(line) & 0xffffffffL;
            long n0 = n1.remainder(r32).longValue();
            check(((n0inv * n0) + 1) % (1L << 32) == 0, "n0inv * n !≡ -1 (mod 2^32)");

            BigInteger rr = AdbKey.rrFromPublicKeyLine(line);
            BigInteger rrExpect = BigInteger.ONE.shiftLeft(4096).mod(n1);
            check(rr.equals(rrExpect), "rr != 2^4096 mod n");

            byte[] der = a.certificate().getEncoded();
            X509Certificate parsed = (X509Certificate)
                    CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.ByteArrayInputStream(der));
            parsed.verify(parsed.getPublicKey());
            parsed.checkValidity();
            check(parsed.getSubjectX500Principal().equals(parsed.getIssuerX500Principal()), "not self-signed issuer");
            String cn = parsed.getSubjectX500Principal().getName(X500Principal.RFC2253);
            check(cn.contains("CN=backstage-test"), "CN mismatch: " + cn);

            // Struct size
            String b64 = line.substring(0, line.indexOf(' '));
            check(Base64.getDecoder().decode(b64).length == AdbKey.ANDROID_PUBKEY_ENCODED_SIZE, "struct size");
        } finally {
            deleteTree(dir);
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void deleteTree(java.nio.file.Path dir) throws java.io.IOException {
        if (dir == null || !java.nio.file.Files.exists(dir)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (java.io.IOException ignored) {
                        }
                    });
        }
    }
}
