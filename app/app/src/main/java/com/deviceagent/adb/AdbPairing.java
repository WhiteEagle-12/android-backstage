package com.deviceagent.adb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLSocket;

/**
 * ADB wireless pairing ({@code adb pair}): TLS 1.3, SPAKE2, encrypted PeerInfo.
 *
 * Sequence (AOSP {@code pairing_connection.cpp}): TCP → TLS handshake → export 64 bytes of
 * keying material with label {@code "adb-label\0"} → SPAKE2 password = {@code code || keyingMaterial}
 * → exchange SPAKE2 messages → HKDF/AES-128-GCM PeerInfo (RSA pubkey line).
 *
 * Packet types are proto3 enum values {@code SPAKE2_MSG=0}, {@code PEER_INFO=1} (not 1/2).
 */
public final class AdbPairing {
    /** Package-private hook for host tests: skip TLS exporter and use this 64-byte value. */
    static volatile byte[] exportedKeyingMaterialOverride;

    private AdbPairing() {}

    /** Pairs with adbd's pairing service (Developer options → Wireless debugging → Pair with code). */
    public static void pair(String host, int port, String code, AdbKey key, int timeoutMs)
            throws IOException {
        pair(host, port, code, key, timeoutMs, exportedKeyingMaterialOverride);
    }

    static void pair(
            String host, int port, String code, AdbKey key, int timeoutMs, byte[] keyingMaterialOverride)
            throws IOException {
        if (code == null || code.isEmpty()) {
            throw new IOException("pairing code is empty");
        }
        Socket plain = new Socket();
        try {
            plain.setTcpNoDelay(true);
            int timeout = timeoutMs > 0 ? timeoutMs : 15_000;
            plain.connect(new InetSocketAddress(host, port), timeout);
            plain.setSoTimeout(timeout);

            SSLSocket ssl;
            try {
                ssl = (SSLSocket) key.sslContext().getSocketFactory().createSocket(plain, host, port, true);
            } catch (GeneralSecurityException e) {
                throw new IOException("TLS context failed", e);
            }
            ssl.setUseClientMode(true);
            ssl.setEnabledProtocols(new String[] {"TLSv1.3"});
            ssl.startHandshake();

            byte[] km;
            if (keyingMaterialOverride != null) {
                km = keyingMaterialOverride;
            } else {
                km = TlsSupport.exportKeyingMaterial(ssl);
            }
            if (km == null || km.length != AdbProtocol.EXPORTED_KEY_SIZE) {
                throw new IOException("exported keying material must be 64 bytes");
            }

            byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
            byte[] password = Bytes.concat(codeBytes, km);

            Spake2 spake = Spake2.adbClient();
            byte[] myMsg = spake.generateMsg(password);

            DataInputStream in = new DataInputStream(ssl.getInputStream());
            DataOutputStream out = new DataOutputStream(ssl.getOutputStream());

            writePacket(out, AdbProtocol.PAIRING_SPAKE2_MSG, myMsg);
            PairingHeader theirHeader = readHeader(in);
            if (theirHeader.type != AdbProtocol.PAIRING_SPAKE2_MSG) {
                throw new IOException("expected SPAKE2_MSG, got type=" + theirHeader.type);
            }
            byte[] theirMsg = new byte[theirHeader.payload];
            Bytes.readFully(in, theirMsg, 0, theirMsg.length);
            byte[] spakeKey;
            try {
                spakeKey = spake.processMsg(theirMsg);
            } catch (RuntimeException e) {
                throw new IOException("SPAKE2 failed (wrong pairing code?)", e);
            }

            Aes128Gcm gcm = new Aes128Gcm(spakeKey);
            byte[] peerInfo = new byte[AdbProtocol.PEER_INFO_SIZE];
            peerInfo[0] = AdbProtocol.PEER_INFO_ADB_RSA_PUB_KEY;
            byte[] pubLine = key.publicKeyLine().getBytes(StandardCharsets.UTF_8);
            int copy = Math.min(pubLine.length, AdbProtocol.PEER_INFO_SIZE - 1);
            System.arraycopy(pubLine, 0, peerInfo, 1, copy);

            byte[] encrypted = gcm.encrypt(peerInfo);
            writePacket(out, AdbProtocol.PAIRING_PEER_INFO, encrypted);

            PairingHeader infoHeader = readHeader(in);
            if (infoHeader.type != AdbProtocol.PAIRING_PEER_INFO) {
                throw new IOException("expected PEER_INFO, got type=" + infoHeader.type);
            }
            byte[] theirEnc = new byte[infoHeader.payload];
            Bytes.readFully(in, theirEnc, 0, theirEnc.length);
            byte[] decrypted;
            try {
                decrypted = gcm.decrypt(theirEnc);
            } catch (SecurityException e) {
                throw new IOException("pairing PeerInfo decrypt failed (wrong code?)", e);
            }
            if (decrypted.length != AdbProtocol.PEER_INFO_SIZE) {
                throw new IOException("PeerInfo size mismatch: " + decrypted.length);
            }
        } finally {
            try {
                plain.close();
            } catch (IOException ignored) {
            }
        }
    }

    static void writePacket(DataOutputStream out, byte type, byte[] payload) throws IOException {
        byte[] header = new byte[AdbProtocol.PAIRING_HEADER_SIZE];
        header[0] = AdbProtocol.PAIRING_VERSION;
        header[1] = type;
        Bytes.putU32BE(header, 2, payload.length);
        out.write(header);
        out.write(payload);
        out.flush();
    }

    static PairingHeader readHeader(DataInputStream in) throws IOException {
        byte[] h = new byte[AdbProtocol.PAIRING_HEADER_SIZE];
        Bytes.readFully(in, h, 0, h.length);
        int version = h[0] & 0xff;
        int type = h[1] & 0xff;
        int payload = Bytes.getU32BE(h, 2);
        if (version != AdbProtocol.PAIRING_VERSION) {
            throw new IOException("pairing header version mismatch: " + version);
        }
        if (type != (AdbProtocol.PAIRING_SPAKE2_MSG & 0xff)
                && type != (AdbProtocol.PAIRING_PEER_INFO & 0xff)) {
            throw new IOException("unknown pairing packet type=" + type);
        }
        if (payload <= 0 || payload > AdbProtocol.PEER_INFO_SIZE * 2) {
            throw new IOException("pairing payload size out of range: " + payload);
        }
        return new PairingHeader((byte) version, (byte) type, payload);
    }

    static final class PairingHeader {
        final byte version;
        final byte type;
        final int payload;

        PairingHeader(byte version, byte type, int payload) {
            this.version = version;
            this.type = type;
            this.payload = payload;
        }
    }
}
