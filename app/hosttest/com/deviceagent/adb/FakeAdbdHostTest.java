package com.deviceagent.adb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;

/**
 * Host self-test 5: fake adbd over loopback — CNXN/STLS/TLS/CNXN then {@code shell:echo hi}.
 */
public final class FakeAdbdHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("FakeAdbdHostTest OK");
    }

    static void run(PrintStream out) throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("fakeadbd");
        AdbKey clientKey = AdbKey.loadOrCreate(dir.resolve("client").toFile(), "client");
        AdbKey serverKey = AdbKey.loadOrCreate(dir.resolve("server").toFile(), "adbd");

        ServerSocket ss = new ServerSocket(0);
        ss.setReuseAddress(true);
        int port = ss.getLocalPort();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> serverErr = new AtomicReference<>();
        Thread server = new Thread(() -> {
            ready.countDown();
            try (ServerSocket hold = ss) {
                Socket plain = hold.accept();
                plain.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(plain.getInputStream());
                DataOutputStream outStream = new DataOutputStream(plain.getOutputStream());

                AdbMessage cnxn = AdbConnection.read(in);
                if (cnxn.command != AdbProtocol.A_CNXN) {
                    throw new IOException("expected CNXN, got " + AdbProtocol.cmdName(cnxn.command));
                }
                AdbConnection.write(outStream, new AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, (byte[]) null));
                AdbMessage stls = AdbConnection.read(in);
                if (stls.command != AdbProtocol.A_STLS) {
                    throw new IOException("expected STLS, got " + AdbProtocol.cmdName(stls.command));
                }

                SSLSocket ssl = (SSLSocket)
                        serverKey.sslContext()
                                .getSocketFactory()
                                .createSocket(plain, "127.0.0.1", plain.getPort(), true);
                ssl.setUseClientMode(false);
                ssl.setNeedClientAuth(false);
                ssl.setEnabledProtocols(new String[] {"TLSv1.3"});
                ssl.startHandshake();
                in = new DataInputStream(ssl.getInputStream());
                outStream = new DataOutputStream(ssl.getOutputStream());

                AdbConnection.write(
                        outStream,
                        new AdbMessage(
                                AdbProtocol.A_CNXN,
                                AdbProtocol.A_VERSION,
                                AdbProtocol.A_MAXDATA,
                                "device::\0".getBytes(StandardCharsets.UTF_8)));

                serveShells(in, outStream);
            } catch (Throwable t) {
                serverErr.set(t);
            }
        }, "fake-adbd");
        server.setDaemon(true);
        server.start();
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("server did not start");
        }

        try (AdbConnection conn = AdbConnection.connect("127.0.0.1", port, clientKey, 10_000)) {
            check(conn.deviceBanner().startsWith("device::"), "banner: " + conn.deviceBanner());
            String first = conn.shell("echo hi", 10_000);
            check("hi\n".equals(first), "first shell: [" + first + "]");
            String second = conn.shell("echo hi", 10_000);
            check("hi\n".equals(second), "second shell: [" + second + "]");
        }

        server.join(5_000);
        if (serverErr.get() != null) {
            throw new AssertionError("fake adbd failed", serverErr.get());
        }
    }

    private static void serveShells(DataInputStream in, DataOutputStream out) throws IOException {
        int nextRemote = 1;
        int served = 0;
        while (served < 2) {
            AdbMessage open;
            try {
                open = AdbConnection.read(in);
            } catch (IOException e) {
                break;
            }
            if (open.command == AdbProtocol.A_CLSE || open.command == AdbProtocol.A_OKAY) {
                continue;
            }
            if (open.command != AdbProtocol.A_OPEN) {
                throw new IOException("expected OPEN, got " + AdbProtocol.cmdName(open.command));
            }
            int localId = open.arg0;
            int remoteId = nextRemote++;
            AdbConnection.write(out, new AdbMessage(AdbProtocol.A_OKAY, remoteId, localId, (byte[]) null));
            String dest = open.dataAsString();
            byte[] payload;
            if (dest.startsWith("shell:echo hi")) {
                payload = "hi\n".getBytes(StandardCharsets.UTF_8);
            } else {
                payload = ("unexpected:" + dest + "\n").getBytes(StandardCharsets.UTF_8);
            }
            AdbConnection.write(out, new AdbMessage(AdbProtocol.A_WRTE, remoteId, localId, payload));
            AdbMessage ack = AdbConnection.read(in);
            if (ack.command != AdbProtocol.A_OKAY) {
                throw new IOException("expected OKAY after WRTE, got " + AdbProtocol.cmdName(ack.command));
            }
            AdbConnection.write(out, new AdbMessage(AdbProtocol.A_CLSE, remoteId, localId, (byte[]) null));
            served++;
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
