package com.deviceagent.adb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;

/**
 * Host self-test 6: fake pairing server with stubbed 64-byte keying material.
 */
public final class FakePairingHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("FakePairingHostTest OK");
    }

    static void run(PrintStream out) throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("fakepair");
        AdbKey clientKey = AdbKey.loadOrCreate(dir.resolve("client").toFile(), "pair-client");
        AdbKey serverKey = AdbKey.loadOrCreate(dir.resolve("server").toFile(), "pair-server");

        byte[] km = new byte[64];
        Arrays.fill(km, (byte) 0x5a);
        AdbPairing.exportedKeyingMaterialOverride = km;

        ServerSocket ss = new ServerSocket(0);
        ss.setReuseAddress(true);
        int port = ss.getLocalPort();
        String code = "123456";
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<String> receivedPub = new AtomicReference<>();
        AtomicReference<Throwable> serverErr = new AtomicReference<>();
        Thread server = new Thread(() -> {
            ready.countDown();
            try (ServerSocket hold = ss) {
                Socket plain = hold.accept();
                plain.setTcpNoDelay(true);
                SSLSocket ssl = (SSLSocket)
                        serverKey.sslContext()
                                .getSocketFactory()
                                .createSocket(plain, "127.0.0.1", plain.getPort(), true);
                ssl.setUseClientMode(false);
                ssl.setNeedClientAuth(false);
                ssl.setEnabledProtocols(new String[] {"TLSv1.3"});
                ssl.startHandshake();

                byte[] password = Bytes.concat(code.getBytes(StandardCharsets.UTF_8), km);
                Spake2 spake = Spake2.adbServer();
                byte[] myMsg = spake.generateMsg(password);

                DataInputStream in = new DataInputStream(ssl.getInputStream());
                DataOutputStream outStream = new DataOutputStream(ssl.getOutputStream());

                AdbPairing.writePacket(outStream, AdbProtocol.PAIRING_SPAKE2_MSG, myMsg);
                AdbPairing.PairingHeader h = AdbPairing.readHeader(in);
                if (h.type != AdbProtocol.PAIRING_SPAKE2_MSG) {
                    throw new IOException("expected SPAKE2_MSG");
                }
                byte[] theirMsg = new byte[h.payload];
                Bytes.readFully(in, theirMsg, 0, theirMsg.length);
                byte[] key = spake.processMsg(theirMsg);
                Aes128Gcm gcm = new Aes128Gcm(key);

                byte[] peerInfo = new byte[AdbProtocol.PEER_INFO_SIZE];
                peerInfo[0] = AdbProtocol.PEER_INFO_ADB_RSA_PUB_KEY;
                byte[] guid = "fake-adbd-guid".getBytes(StandardCharsets.UTF_8);
                System.arraycopy(guid, 0, peerInfo, 1, guid.length);
                byte[] enc = gcm.encrypt(peerInfo);
                AdbPairing.writePacket(outStream, AdbProtocol.PAIRING_PEER_INFO, enc);

                AdbPairing.PairingHeader infoH = AdbPairing.readHeader(in);
                if (infoH.type != AdbProtocol.PAIRING_PEER_INFO) {
                    throw new IOException("expected PEER_INFO");
                }
                byte[] theirEnc = new byte[infoH.payload];
                Bytes.readFully(in, theirEnc, 0, theirEnc.length);
                byte[] dec = gcm.decrypt(theirEnc);
                int end = 1;
                while (end < dec.length && dec[end] != 0) {
                    end++;
                }
                receivedPub.set(new String(dec, 1, end - 1, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                serverErr.set(t);
            }
        }, "fake-pairing");
        server.setDaemon(true);
        server.start();
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("pairing server did not start");
        }

        try {
            AdbPairing.pair("127.0.0.1", port, code, clientKey, 15_000);
        } finally {
            AdbPairing.exportedKeyingMaterialOverride = null;
        }

        server.join(10_000);
        if (serverErr.get() != null) {
            throw new AssertionError("fake pairing server failed", serverErr.get());
        }
        String got = receivedPub.get();
        check(got != null, "server did not receive PeerInfo");
        check(got.equals(clientKey.publicKeyLine()), "pubkey mismatch:\n got=" + got + "\n want=" + clientKey.publicKeyLine());
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
