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
 * ADB-over-TLS client ({@code adb connect} / STLS).
 *
 * <p><strong>Not thread-safe.</strong> All methods must be used from a single thread.
 * Sequential {@link #shell} / {@link #shellDetached} calls on one connection are supported;
 * local stream ids increment.
 *
 * Flow: TCP → CNXN (version {@code 0x01000001}, maxdata 1 MiB) → STLS → TLS 1.3 handshake →
 * CNXN over TLS. If adbd sends AUTH instead of STLS (plain {@code adb tcpip}), the paired key
 * signs its token.
 */
public final class AdbConnection implements AutoCloseable {
    private final Socket plain;
    private final SSLSocket ssl;
    private DataInputStream in;
    private DataOutputStream out;
    private String deviceBanner = "";
    private int nextLocalId = 1;
    private boolean closed;

    private AdbConnection(Socket plain, SSLSocket ssl, DataInputStream in, DataOutputStream out, String banner) {
        this.plain = plain;
        this.ssl = ssl;
        this.in = in;
        this.out = out;
        this.deviceBanner = banner == null ? "" : banner;
    }

    public static AdbConnection connect(String host, int port, AdbKey key, int timeoutMs)
            throws IOException {
        Socket socket = new Socket();
        int timeout = timeoutMs > 0 ? timeoutMs : 15_000;
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeout);
        socket.setSoTimeout(timeout);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        try {
            write(out, new AdbMessage(
                    AdbProtocol.A_CNXN,
                    AdbProtocol.A_VERSION,
                    AdbProtocol.A_MAXDATA,
                    AdbProtocol.CONNECT_BANNER + "\0"));

            AdbMessage msg = read(in);
            SSLSocket sslSocket = null;
            if (msg.command == AdbProtocol.A_STLS) {
                write(out, new AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, (byte[]) null));
                try {
                    sslSocket = (SSLSocket)
                            key.sslContext().getSocketFactory().createSocket(socket, host, port, true);
                } catch (GeneralSecurityException e) {
                    throw new IOException("TLS context failed", e);
                }
                sslSocket.setUseClientMode(true);
                sslSocket.setEnabledProtocols(new String[] {"TLSv1.3"});
                sslSocket.startHandshake();
                in = new DataInputStream(sslSocket.getInputStream());
                out = new DataOutputStream(sslSocket.getOutputStream());
                msg = read(in);
            } else if (msg.command == AdbProtocol.A_AUTH) {
                // Plain TCP adbd (adb tcpip, as Shizuku sets up). Sign its token with the
                // already-paired key; never offer the public key, which would prompt the user.
                if (msg.arg0 != AUTH_TOKEN || msg.data == null) throw new IOException("unexpected adbd AUTH");
                write(out, new AdbMessage(AdbProtocol.A_AUTH, AUTH_SIGNATURE, 0, sign(key, msg.data)));
                msg = read(in);
                if (msg.command == AdbProtocol.A_AUTH) throw new IOException("adbd did not accept the paired key");
            }

            if (msg.command != AdbProtocol.A_CNXN) {
                throw new IOException("expected A_CNXN after handshake, got " + AdbProtocol.cmdName(msg.command));
            }
            return new AdbConnection(socket, sslSocket, in, out, msg.dataAsString());
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    /** adbd verifies the token as a SHA-1 digest with PKCS#1 v1.5 padding (RSA_sign NID_sha1). */
    static byte[] sign(AdbKey key, byte[] token) throws IOException {
        try {
            byte[] info = new byte[SHA1_DIGEST_INFO.length + token.length];
            System.arraycopy(SHA1_DIGEST_INFO, 0, info, 0, SHA1_DIGEST_INFO.length);
            System.arraycopy(token, 0, info, SHA1_DIGEST_INFO.length, token.length);
            java.security.Signature rsa = java.security.Signature.getInstance("NONEwithRSA");
            rsa.initSign(key.keyPair().getPrivate());
            rsa.update(info);
            return rsa.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign adbd token", e);
        }
    }

    public String deviceBanner() {
        return deviceBanner;
    }

    /**
     * Runs {@code shell:<command>}, waits for CLSE, returns combined output (adbd merges stdout/stderr).
     */
    public String shell(String command, long timeoutMs) throws IOException {
        return openShell(command, timeoutMs, false);
    }

    /**
     * Like {@link #shell} but returns as soon as the stream has produced output or closed;
     * used to start a {@code nohup ... &} daemon. Sends CLSE after returning.
     */
    public String shellDetached(String command, long timeoutMs) throws IOException {
        return openShell(command, timeoutMs, true, false);
    }

    /**
     * Runs {@code exec:<command>}: no PTY is allocated, so closing the stream does not send
     * SIGHUP to the command's children. Required for launching a detached daemon that must
     * outlive this connection ({@code setsid nohup app_process ... &}); a PTY-backed
     * {@code shell:} tears the whole process group down when adbd closes the socket.
     */
    public String exec(String command, long timeoutMs) throws IOException {
        return openShell(command, timeoutMs, false, true);
    }

    private String openShell(String command, long timeoutMs, boolean detached) throws IOException {
        return openShell(command, timeoutMs, detached, false);
    }

    private String openShell(String command, long timeoutMs, boolean detached, boolean exec) throws IOException {
        ensureOpen();
        int localId = nextLocalId++;
        String dest = (exec ? "exec:" : "shell:") + command + "\0";
        write(out, new AdbMessage(AdbProtocol.A_OPEN, localId, 0, dest.getBytes(StandardCharsets.UTF_8)));

        long deadline = timeoutMs <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + timeoutMs;
        StringBuilder acc = new StringBuilder();
        int remoteId = 0;
        boolean opened = false;
        try {
            while (System.currentTimeMillis() < deadline) {
                applyTimeout(deadline);
                AdbMessage msg = read(in);
                if (msg.command == AdbProtocol.A_OKAY) {
                    if (msg.arg1 != localId && opened) {
                        continue;
                    }
                    remoteId = msg.arg0;
                    opened = true;
                } else if (msg.command == AdbProtocol.A_WRTE) {
                    remoteId = msg.arg0;
                    opened = true;
                    if (msg.data.length > 0) {
                        acc.append(new String(msg.data, StandardCharsets.UTF_8));
                    }
                    write(out, new AdbMessage(AdbProtocol.A_OKAY, localId, remoteId, (byte[]) null));
                    if (detached) {
                        break;
                    }
                } else if (msg.command == AdbProtocol.A_CLSE) {
                    remoteId = msg.arg0;
                    write(out, new AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, (byte[]) null));
                    return acc.toString();
                } else {
                    throw new IOException("unexpected " + AdbProtocol.cmdName(msg.command) + " on shell stream");
                }
            }
            if (detached) {
                write(out, new AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, (byte[]) null));
                return acc.toString();
            }
            throw new IOException("shell timed out after " + timeoutMs + " ms");
        } catch (java.net.SocketTimeoutException e) {
            try {
                write(out, new AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, (byte[]) null));
            } catch (IOException ignored) {
            }
            if (detached) {
                return acc.toString();
            }
            throw new IOException("shell timed out after " + timeoutMs + " ms", e);
        }
    }

    private void applyTimeout(long deadline) throws IOException {
        if (deadline == Long.MAX_VALUE) {
            return;
        }
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            throw new java.net.SocketTimeoutException("deadline");
        }
        Socket s = ssl != null ? ssl : plain;
        s.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("AdbConnection closed");
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (in != null) {
                in.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (ssl != null) {
                ssl.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (plain != null) {
                plain.close();
            }
        } catch (IOException ignored) {
        }
    }

    static void write(DataOutputStream out, AdbMessage msg) throws IOException {
        out.write(msg.encode());
        out.flush();
    }

    static AdbMessage read(DataInputStream in) throws IOException {
        byte[] header = new byte[AdbMessage.HEADER_LENGTH];
        Bytes.readFully(in, header, 0, header.length);
        AdbMessage h = AdbMessage.decodeHeader(header);
        if (h.magic != (h.command ^ 0xffffffff)) {
            throw new IOException("bad ADB magic on " + AdbProtocol.cmdName(h.command));
        }
        if (h.dataLength < 0 || h.dataLength > AdbProtocol.A_MAXDATA) {
            throw new IOException("ADB payload too large: " + h.dataLength);
        }
        byte[] data = new byte[h.dataLength];
        if (h.dataLength > 0) {
            Bytes.readFully(in, data, 0, h.dataLength);
        }
        return new AdbMessage(h.command, h.arg0, h.arg1, h.dataLength, h.dataCheck, h.magic, data);
    }
}
