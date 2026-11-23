package com.deviceagent.adb;

import java.lang.reflect.Method;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * TLS helpers: trust-all manager, RSA client/server KeyManager, and
 * {@code exportKeyingMaterial} via API 31+ {@code android.net.ssl.SSLSockets} then Conscrypt.
 */
final class TlsSupport {
    static final String EXPORTED_KEY_LABEL = AdbProtocol.EXPORTED_KEY_LABEL;
    static final int EXPORTED_KEY_SIZE = AdbProtocol.EXPORTED_KEY_SIZE;

    private TlsSupport() {}

    static X509ExtendedTrustManager trustAll() {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    static X509ExtendedKeyManager rsaKeyManager(PrivateKey privateKey, X509Certificate cert) {
        return new X509ExtendedKeyManager() {
            private static final String ALIAS = "key";

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return "RSA".equals(keyType) ? new String[] {ALIAS} : null;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return "RSA".equals(keyType) ? new String[] {ALIAS} : null;
            }

            @Override
            public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
                if (keyTypes == null) {
                    return ALIAS;
                }
                for (String t : keyTypes) {
                    if ("RSA".equals(t)) {
                        return ALIAS;
                    }
                }
                return ALIAS;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return ALIAS;
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return chooseClientAlias(keyType, issuers, null);
            }

            @Override
            public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                return ALIAS;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? new X509Certificate[] {cert} : null;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return ALIAS.equals(alias) ? privateKey : null;
            }
        };
    }

    /**
     * Export 64 bytes of TLS keying material with label {@code "adb-label\0"} and no context.
     * Tries {@code android.net.ssl.SSLSockets.exportKeyingMaterial} (API 31+), then Conscrypt
     * {@code exportKeyingMaterial(SSLSocket, String, byte[], int)}, then a method of that name
     * on the socket class itself.
     */
    static byte[] exportKeyingMaterial(SSLSocket socket) throws java.io.IOException {
        String label = EXPORTED_KEY_LABEL;
        int length = EXPORTED_KEY_SIZE;
        byte[] context = null;

        byte[] out = invokeStatic(
                "android.net.ssl.SSLSockets",
                "exportKeyingMaterial",
                new Class<?>[] {SSLSocket.class, String.class, byte[].class, int.class},
                socket,
                label,
                context,
                length);
        if (out != null) {
            return out;
        }
        for (String conscrypt : new String[] {
            "com.android.org.conscrypt.Conscrypt", "org.conscrypt.Conscrypt"
        }) {
            out = invokeStatic(
                    conscrypt,
                    "exportKeyingMaterial",
                    new Class<?>[] {SSLSocket.class, String.class, byte[].class, int.class},
                    socket,
                    label,
                    context,
                    length);
            if (out != null) {
                return out;
            }
        }
        try {
            Method m = socket.getClass().getMethod("exportKeyingMaterial", String.class, byte[].class, int.class);
            Object r = m.invoke(socket, label, context, length);
            if (r instanceof byte[]) {
                return (byte[]) r;
            }
        } catch (ReflectiveOperationException ignored) {
            // host JDK: no exporter
        }
        throw new java.io.IOException(
                "TLS exportKeyingMaterial is unavailable (need Conscrypt / API 31+ SSLSockets)");
    }

    private static byte[] invokeStatic(String className, String method, Class<?>[] types, Object... args) {
        try {
            Class<?> c = Class.forName(className);
            Method m = c.getMethod(method, types);
            Object r = m.invoke(null, args);
            return r instanceof byte[] ? (byte[]) r : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
