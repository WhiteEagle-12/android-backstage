package com.deviceagent.rpc;

import com.deviceagent.app.BuildInfo;
import com.deviceagent.crypto.LinkStore;
import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.daemon.DaemonLog;
import com.deviceagent.shell.DaemonNode;
import com.deviceagent.shell.VirtualDisplayHost;
import com.deviceagent.tools.AndroidTools;
import com.deviceagent.tools.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Inbound control on :18422 — encrypted {@code /call}, health, configure, shutdown.
 */
public final class ControlServer {
    public static final int PORT = 18422;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int HEADER_LIMIT = 64 * 1024;

    private final AndroidTools tools;
    private final DaemonConfig config;
    private final LinkStore links;
    private final DaemonNode daemon;
    private final VirtualDisplayHost virtualDisplay;
    private final Runnable onShutdown;
    private final long startedAt = System.currentTimeMillis();
    private Thread thread;
    private ServerSocket server;
    private java.util.concurrent.ExecutorService pool;
    private int port = PORT;
    private volatile java.util.function.Supplier<Map<String, Object>> extraHealth = Map::of;

    public ControlServer(
            AndroidTools tools,
            DaemonConfig config,
            LinkStore links,
            DaemonNode daemon,
            VirtualDisplayHost virtualDisplay,
            Runnable onShutdown) {
        this.tools = tools;
        this.config = config == null ? new DaemonConfig() : config;
        this.links = links;
        this.daemon = daemon;
        this.virtualDisplay = virtualDisplay;
        this.onShutdown = onShutdown;
    }

    /** Listen on a different port than the default (daemon.json "port"). Call before start(). */
    public ControlServer port(int port) {
        if (port > 0 && port < 65536) this.port = port;
        return this;
    }

    public int port() {
        return port;
    }

    /** Extra loopback-only health fields (Wi-Fi trust, network). */
    public ControlServer extraHealth(java.util.function.Supplier<Map<String, Object>> supplier) {
        if (supplier != null) this.extraHealth = supplier;
        return this;
    }

    /** Binds the port synchronously so the caller knows whether it owns it. */
    public boolean start() {
        if (thread != null && thread.isAlive()) return true;
        try {
            // Bind loopback by default so untrusted LAN/Wi-Fi peers cannot reach the daemon unless direct LAN mode is enabled.
            String bindHost = config.allowLanDirect ? "0.0.0.0" : "127.0.0.1";
            server = new ServerSocket(port, 32, InetAddress.getByName(bindHost));
        } catch (IOException e) {
            DaemonLog.print("bind :" + port + " failed: " + e.getMessage());
            server = null;
            return false;
        }
        pool = new java.util.concurrent.ThreadPoolExecutor(
                2, 16, 30L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "backstage-inbound-req");
                    t.setDaemon(true);
                    return t;
                });
        thread = new Thread(this::run, "backstage-inbound");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public void stop() {
        ServerSocket current = server;
        server = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (thread != null) thread.interrupt();
    }

    private void run() {
        ServerSocket ss = server;
        if (ss == null) return;
        try (ss) {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket sock = ss.accept();
                    java.util.concurrent.ExecutorService exec = pool;
                    if (exec == null) {
                        try { sock.close(); } catch (IOException ignored) {}
                        return;
                    }
                    try {
                        exec.execute(() -> {
                            try (Socket s = sock) {
                                handle(s);
                            } catch (IOException ignored) {
                            } catch (Throwable e) {
                                DaemonLog.print("inbound handler crashed", e);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        try { sock.close(); } catch (IOException ignored) {}
                    }
                } catch (IOException e) {
                    if (server == null) return;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket sock) throws IOException {
        sock.setTcpNoDelay(true);
        InputStream in = new java.io.BufferedInputStream(sock.getInputStream(), 16 * 1024);
        boolean first = true;
        while (server != null) {
            sock.setSoTimeout(first ? 60_000 : 30_000);
            first = false;
            HttpReq req;
            try {
                req = readRequest(in);
            } catch (java.net.SocketTimeoutException e) {
                return;
            }
            if (req == null) return;
            boolean keepAlive = !"close".equalsIgnoreCase(req.header("connection"));
            if (req.tooLarge) {
                writeJson(sock, 413, "{\"error\":\"payload too large\"}", null, false);
                return;
            }
            String session = req.header("mcp-session-id");
            if (session == null || session.isBlank()) session = UUID.randomUUID().toString();

            if ("OPTIONS".equals(req.method)) {
                write(sock, 204, "application/json", new byte[0], session, !keepAlive);
                if (!keepAlive) return;
                continue;
            }

            if ("GET".equals(req.method) && "/health".equals(req.path)) {
                // Other devices on the network (and the PC's direct route) see only identity and liveness.
                writeJson(sock, 200, sock.getInetAddress().isLoopbackAddress() ? healthJson() : publicHealthJson(), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }

            if ("POST".equals(req.method) && "/call".equals(req.path)) {
                sock.setSoTimeout(640_000);
                if (!handleCall(sock, req, session, keepAlive)) return;
                continue;
            }

            // Administration is app-local even when encrypted /call is exposed on LAN.
            if (!sock.getInetAddress().isLoopbackAddress() || !authorized(req)) {
                writeJson(sock, 401, "{\"error\":\"unauthorized\"}", session, false);
                return;
            }

            // The app holds this open; it closes the moment this process dies, so the app relaunches at once.
            if ("GET".equals(req.method) && "/watch".equals(req.path)) {
                handleWatch(sock);
                return;
            }
            if ("GET".equals(req.method) && "/session".equals(req.path)) {
                writeJson(sock, 200, Json.stringify(Json.mapOf("agents", links.agentIds(), "permits", links.permits.snapshot())), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/configure".equals(req.path)) {
                handleConfigure(sock, req, session);
                return;
            }
            if ("POST".equals(req.method) && "/reconnect".equals(req.path)) {
                Map<String, Object> status = daemon.reconnect();
                status.put("linkedAgents", links.agentIds());
                writeJson(sock, 200, Json.stringify(status), session, false);
                return;
            }
            if ("POST".equals(req.method) && "/fix-usb-config".equals(req.path)) {
                boolean fixed = com.deviceagent.shell.SystemBinders.clearUsbScreenUnlockedFunctions();
                String now = com.deviceagent.shell.SystemBinders.usbScreenUnlockedFunctions();
                writeJson(sock, fixed ? 200 : 500, Json.stringify(Json.mapOf("ok", fixed, "usbUnlockedFunctions", now)), session, false);
                return;
            }
            if ("GET".equals(req.method) && "/blocked-apps".equals(req.path)) {
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "packages", tools.privacyFence().list())), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/blocked-apps".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                List<String> pkgs = new java.util.ArrayList<>();
                if (body.get("packages") instanceof List<?> list) {
                    for (Object item : list) if (item != null) pkgs.add(String.valueOf(item));
                }
                List<String> saved = tools.privacyFence().set(pkgs);
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "packages", saved)), session, false);
                return;
            }
            if ("GET".equals(req.method) && "/shell-access".equals(req.path)) {
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "enabled", tools.allowShell())), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/shell-access".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                boolean saved = tools.setAllowShell(Boolean.TRUE.equals(body.get("enabled")));
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "enabled", saved)), session, false);
                return;
            }
            if ("GET".equals(req.method) && "/timeline".equals(req.path)) {
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "entries", tools.timeline().recent(100))), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/timeline/clear".equals(req.path)) {
                tools.timeline().clear();
                writeJson(sock, 200, "{\"ok\":true}", session, false);
                return;
            }
            if ("GET".equals(req.method) && "/sessions".equals(req.path)) {
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "sessions", tools.listAllSessions())), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("GET".equals(req.method) && "/preview".equals(req.path)) {
                handlePreview(sock, req, session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/session/takeover".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                String sid = Json.string(body, "sessionId");
                boolean active = Boolean.TRUE.equals(body.get("active"));
                String confirmId = Json.string(body, "confirmId");
                Map<String, Object> res = tools.setUserTakeoverAdmin(sid, active, confirmId);
                writeJson(sock, Boolean.TRUE.equals(res.get("ok")) ? 200 : 400, Json.stringify(res), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/session/input".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                String sid = Json.string(body, "sessionId");
                Integer disp = body.get("displayId") instanceof Number n ? n.intValue() : null;
                Map<String, Object> res = tools.userInputAdmin(sid, disp, body);
                writeJson(sock, Boolean.TRUE.equals(res.get("ok")) ? 200 : 400, Json.stringify(res), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/session/handoff".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                Integer disp = body.get("displayId") instanceof Number n ? n.intValue() : null;
                Map<String, Object> res = tools.handoffSessionAdmin(Json.string(body, "sessionId"), disp);
                writeJson(sock, Boolean.TRUE.equals(res.get("ok")) ? 200 : 400, Json.stringify(res), session, false);
                return;
            }
            if ("POST".equals(req.method) && "/session/stop".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                String sid = Json.string(body, "sessionId");
                if (sid == null || sid.isBlank()) {
                    writeJson(sock, 400, "{\"error\":\"sessionId is required\"}", session, false);
                    return;
                }
                Map<String, Object> res = tools.stopSessionAdmin(sid);
                writeJson(sock, Boolean.TRUE.equals(res.get("ok")) ? 200 : 400, Json.stringify(res), session, false);
                return;
            }
            if ("GET".equals(req.method) && "/confirmations".equals(req.path)) {
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "confirmations", tools.confirmations().list(true))), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/confirmations/decide".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                boolean ok = tools.decideConfirmationAdmin(Json.string(body, "confirmId"), Json.string(body, "decision"));
                writeJson(sock, ok ? 200 : 404, Json.stringify(Json.mapOf("ok", ok)), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("GET".equals(req.method) && "/inbox".equals(req.path)) {
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "items", tools.inbox().list(50))), session, keepAlive);
                if (!keepAlive) return;
                continue;
            }
            if ("POST".equals(req.method) && "/inbox".equals(req.path)) {
                Map<String, Object> body = parseObject(req.body);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> files = body.get("files") instanceof List<?> f ? (List<Map<String, Object>>) f : List.of();
                Map<String, Object> added = tools.inbox().add(
                        Json.string(body, "text"), Json.string(body, "subject"),
                        Json.string(body, "mimeType"), Json.string(body, "sourceApp"), files);
                tools.emitEvent(Json.mapOf("type", "share_received", "payload", added));
                writeJson(sock, 200, Json.stringify(Json.mapOf("ok", true, "item", added)), session, false);
                return;
            }
            if ("POST".equals(req.method) && "/shutdown".equals(req.path)) {
                writeJson(sock, 200, "{\"ok\":true}", session, false);
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
                if (onShutdown != null) {
                    Thread t = new Thread(onShutdown, "backstage-shutdown-req");
                    t.setDaemon(false);
                    t.start();
                }
                return;
            }

            writeJson(sock, 404, "{\"error\":\"not found\"}", session, false);
            return;
        }
    }

    private boolean handleCall(Socket sock, HttpReq req, String session, boolean keepAlive) throws IOException {
        Map<String, Object> body = parseObject(req.body);
        Object envelope = body.get("envelope");
        if (envelope == null && body.containsKey("v") && body.containsKey("ct")) {
            envelope = body;
        }
        EnvelopeCall.Outcome outcome = EnvelopeCall.handle(links, config.deviceId, envelope, tools);
        if (outcome.ok()) {
            writeJson(sock, 200, Json.stringify(outcome.localResult()), session, keepAlive);
            return keepAlive;
        } else {
            writeJson(sock, 401, Json.stringify(outcome.localResult()), session, false);
            return false;
        }
    }

    private void handlePreview(Socket sock, HttpReq req, String session, boolean keepAlive) throws IOException {
        if (virtualDisplay == null) {
            writeJson(sock, 404, "{\"error\":\"no virtual display\"}", session, keepAlive);
            return;
        }
        int displayId = -1;
        float scale = 0.5f;
        int quality = 85;
        long sinceFrame = -1L;
        if (req.query != null) {
            for (String part : req.query.split("&")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0, eq), v = part.substring(eq + 1);
                try {
                    if ("displayId".equals(k)) displayId = Integer.parseInt(v);
                    else if ("scale".equals(k)) scale = Float.parseFloat(v);
                    else if ("quality".equals(k)) quality = Integer.parseInt(v);
                    else if ("sinceFrame".equals(k)) sinceFrame = Long.parseLong(v);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (displayId > 0) {
            String top = com.deviceagent.shell.SystemBinders.topActivityOnDisplay(displayId);
            if (tools.privacyFence().isBlocked(tools.privacyFence().packageOfComponent(top))) {
                writeJson(sock, 403, "{\"error\":\"blocked_app\"}", session, keepAlive);
                return;
            }
        }
        android.os.Bundle args = new android.os.Bundle();
        if (displayId > 0) args.putInt("displayId", displayId);
        args.putFloat("scale", scale);
        args.putInt("quality", quality);
        if (sinceFrame > 0) args.putLong("sinceFrame", sinceFrame);
        android.os.Bundle shot = virtualDisplay.handle("screenshot", args);
        if (!shot.getBoolean("ok", false)) {
            writeJson(sock, 404, Json.stringify(Json.mapOf("error", String.valueOf(shot.getString("error")))), session, keepAlive);
            return;
        }
        Map<String, String> extraHeaders = new LinkedHashMap<>();
        long seq = shot.getLong("frameSeq", 0L);
        int sw = shot.getInt("sourceWidth", 0);
        int sh = shot.getInt("sourceHeight", 0);
        if (seq > 0) extraHeaders.put("X-Frame-Seq", String.valueOf(seq));
        if (sw > 0) extraHeaders.put("X-Source-Width", String.valueOf(sw));
        if (sh > 0) extraHeaders.put("X-Source-Height", String.valueOf(sh));
        if (shot.getBoolean("unchanged", false)) {
            write(sock, 304, "application/octet-stream", new byte[0], session, !keepAlive, extraHeaders);
            return;
        }
        byte[] bytes = shot.getByteArray("png");
        String mime = shot.getString("mime", "image/webp");
        if (bytes == null || bytes.length == 0) {
            writeJson(sock, 204, "{}", session, keepAlive);
            return;
        }
        write(sock, 200, mime, bytes, session, !keepAlive, extraHeaders);
    }

    private void handleConfigure(Socket sock, HttpReq req, String session) throws IOException {
        Map<String, Object> body = parseObject(req.body);
        Object permit = body.get("permit");
        if (permit instanceof Map) {
            Map<String,Object> p = (Map<String,Object>)permit;
            Object until = p.get("expiresAt");
            links.permits.set(Json.string(p,"agentId"),Json.string(p,"mode"),Json.string(p,"package"),
                    until instanceof Number ? ((Number)until).longValue() : 0);
        }
        Object addLink = body.get("addLink");
        if (addLink instanceof Map) {
            Map<String, Object> link = Json.object(Json.stringify(addLink));
            String agentId = Json.string(link, "agentId");
            String linkKey = Json.string(link, "linkKey");
            try {
                links.addLink(agentId, linkKey);
            } catch (RuntimeException e) {
                writeJson(sock, 400, Json.stringify(Json.mapOf("error", String.valueOf(e.getMessage()))), session);
                return;
            }
        }
        String forget = Json.string(body, "forgetAgent");
        if (forget != null && !forget.isBlank()) {
            links.forget(forget);
        }
        String url = Json.string(body, "relayUrl");
        String deviceToken = Json.string(body, "deviceToken");
        String deviceName = Json.string(body, "deviceName");
        String deviceId = Json.string(body, "deviceId");
        Map<String, Object> status = daemon.configure(url, deviceToken, deviceName, deviceId);
        status.put("linkedAgents", links.agentIds());
        writeJson(sock, 200, Json.stringify(status), session);
    }

    private void handleWatch(Socket sock) throws IOException {
        sock.setSoTimeout(0);
        OutputStream out = sock.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        while (server != null) {
            out.write(("alive " + android.os.Process.myPid() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private String publicHealthJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("service", "backstage");
        out.put("version", BuildInfo.VERSION);
        out.put("deviceId", config.deviceId == null || config.deviceId.isBlank() ? null : config.deviceId);
        out.put("pid", android.os.Process.myPid());
        out.put("uptimeMs", System.currentTimeMillis() - startedAt);
        out.put("relayConnected", daemon != null && daemon.relayConnected());
        return Json.stringify(out);
    }

    private String healthJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("service", "backstage");
        out.put("version", BuildInfo.VERSION);
        out.put("host", "daemon");
        out.put("uid", android.os.Process.myUid());
        out.put("pid", android.os.Process.myPid());
        out.put("uptimeMs", System.currentTimeMillis() - startedAt);
        Map<String, Object> relay = new LinkedHashMap<>();
        if (daemon != null) {
            relay.put("url", daemon.relayUrl());
            relay.put("connected", daemon.relayConnected());
            relay.put("polling", daemon.polling());
            long last = daemon.lastPollAt();
            relay.put("lastPollAt", last == 0L ? null : last);
            relay.put("registeredName", daemon.registeredName());
            relay.put("lastError", daemon.lastError());
        } else {
            relay.put("url", null);
            relay.put("connected", false);
            relay.put("polling", false);
            relay.put("lastPollAt", null);
            relay.put("registeredName", null);
            relay.put("lastError", null);
        }
        out.put("relay", relay);
        Map<String, Object> vd = new LinkedHashMap<>();
        boolean active = false;
        List<Integer> displayIds = new java.util.ArrayList<>();
        if (virtualDisplay != null) {
            try {
                Map<String, Object> st = com.deviceagent.shell.Bundles.toMap(virtualDisplay.handle("status", null));
                active = Boolean.TRUE.equals(st.get("active"));
                if (st.get("displays") instanceof List<?> list)
                    for (Object item : list) if (item instanceof Map<?, ?> d && d.get("displayId") instanceof Number id) displayIds.add(id.intValue());
            } catch (RuntimeException e) {
                vd.put("error", e.getMessage());
            }
        }
        vd.put("active", active);
        vd.put("count", displayIds.size());
        vd.put("displayIds", displayIds);
        vd.put("displayId", displayIds.isEmpty() ? null : displayIds.get(0));
        out.put("virtualDisplay", vd);
        out.put("linkedAgents", links == null ? List.of() : links.agentIds());
        out.put("blockedCount", tools.privacyFence().count());
        out.put("pendingConfirmations", tools.confirmations().list(false).size());
        String usbFns = com.deviceagent.shell.SystemBinders.usbScreenUnlockedFunctions();
        if (!usbFns.isEmpty()) out.put("usbUnlockedFunctions", usbFns);
        try { out.putAll(extraHealth.get()); } catch (RuntimeException ignored) { }
        return Json.stringify(out);
    }

    private boolean authorized(HttpReq req) {
        String expected = config.localToken == null ? "" : config.localToken;
        if (expected.isEmpty()) return false;
        String auth = req.header("authorization");
        if (auth == null) return false;
        String prefix = "Bearer ";
        if (auth.length() < prefix.length()) return false;
        if (!auth.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        String given = auth.substring(prefix.length()).trim();
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.isBlank()) return Map.of();
        return Json.object(body);
    }

    private static void writeJson(Socket sock, int code, String json, String session) throws IOException {
        writeJson(sock, code, json, session, false);
    }

    private static void writeJson(Socket sock, int code, String json, String session, boolean keepAlive) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        write(sock, code, "application/json; charset=utf-8", bytes, session, !keepAlive);
    }

    private static void write(
            Socket sock, int code, String contentType, byte[] bytes, String session, boolean close)
            throws IOException {
        write(sock, code, contentType, bytes, session, close, null);
    }

    private static void write(
            Socket sock, int code, String contentType, byte[] bytes, String session, boolean close,
            Map<String, String> extraHeaders)
            throws IOException {
        String reason = switch (code) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 413 -> "Payload Too Large";
            case 500 -> "Error";
            default -> "OK";
        };
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        if (code != 304 && code != 204) {
            header.append("Content-Type: ").append(contentType).append("\r\n");
            header.append("Content-Length: ").append(bytes.length).append("\r\n");
        } else {
            header.append("Content-Length: 0\r\n");
        }
        header.append(close ? "Connection: close\r\n" : "Connection: keep-alive\r\nKeep-Alive: timeout=30\r\n");
        header.append(corsHeaders());
        if (session != null) header.append("mcp-session-id: ").append(session).append("\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                header.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        header.append("\r\n");
        OutputStream out = sock.getOutputStream();
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));
        if (bytes.length > 0 && code != 304 && code != 204) out.write(bytes);
        out.flush();
        if (close) {
            try {
                sock.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    private static String corsHeaders() {
        return "";
    }

    private static HttpReq readRequest(InputStream in) throws IOException {
        byte[] headerBuf = new byte[HEADER_LIMIT];
        int n = 0;
        int split = -1;
        while (n < headerBuf.length) {
            int r = in.read();
            if (r < 0) {
                if (n == 0) return null;
                break;
            }
            headerBuf[n++] = (byte) r;
            if (n >= 4
                    && headerBuf[n - 4] == '\r'
                    && headerBuf[n - 3] == '\n'
                    && headerBuf[n - 2] == '\r'
                    && headerBuf[n - 1] == '\n') {
                split = n;
                break;
            }
        }
        if (split < 0) {
            return n == 0 ? null : parseHeaders(headerBuf, n, new byte[0]);
        }
        HttpReq req = parseHeaders(headerBuf, split, null);
        if (req == null) return null;
        int length = req.contentLength;
        if (length < 0) length = 0;
        if (length > MAX_BODY) {
            req.body = "";
            req.tooLarge = true;
            return req;
        }
        byte[] body = new byte[length];
        int got = 0;
        while (got < length) {
            int r = in.read(body, got, length - got);
            if (r < 0) break;
            got += r;
        }
        req.body = new String(body, 0, got, StandardCharsets.UTF_8);
        return req;
    }

    private static HttpReq parseHeaders(byte[] buf, int len, byte[] unused) {
        String text = new String(buf, 0, len, StandardCharsets.US_ASCII);
        String[] lines = text.split("\r\n");
        if (lines.length == 0) return null;
        String[] first = lines[0].split(" ");
        HttpReq req = new HttpReq();
        req.method = first.length > 0 ? first[0].toUpperCase(Locale.US) : "GET";
        String path = first.length > 1 ? first[1] : "/";
        int q = path.indexOf('?');
        req.path = q >= 0 ? path.substring(0, q) : path;
        req.query = q >= 0 ? path.substring(q + 1) : null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            req.headers.put(name, value);
            if (name.equals("content-length")) {
                try {
                    req.contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return req;
    }

    private static final class HttpReq {
        String method = "GET";
        String path = "/";
        String query = null;
        String body = "";
        int contentLength = 0;
        boolean tooLarge;
        final Map<String, String> headers = new LinkedHashMap<>();

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }
    }
}
