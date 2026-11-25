package com.deviceagent.rpc;

import com.deviceagent.crypto.LinkStore;
import com.deviceagent.daemon.DaemonLog;
import com.deviceagent.tools.AndroidTools;
import com.deviceagent.tools.Json;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound relay client. Long-polls {@code /v1/device/next} with the device token.
 * Jobs are {@code { id, envelope }}; results are {@code { id, envelope }} or an
 * unencrypted refusal {@code { id, error: { message: "unauthorized: …" } }}.
 */
public final class DeviceNode {
    public interface IdleHook {
        void onIdle();
    }

    private final String BOOT_ID = java.util.UUID.randomUUID().toString();
    private static final long KEEP_ALIVE_EVERY_MS = 90_000L;
    private static final int NEXT_TIMEOUT_MS = 40_000;
    private static final int HELLO_TIMEOUT_MS = 12_000;
    private static final int RESULT_TIMEOUT_MS = 15_000;
    private static final int ACK_TIMEOUT_MS = 5_000;
    private static final int BACKOFF_MIN_MS = 1_000;
    private static final int BACKOFF_MAX_MS = 30_000;
    /** Jobs this phone runs at once. The relay may grant fewer; older relays grant one. */
    static final int PARALLEL_JOBS = 8;
    private volatile int lanes = 1;
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();

    private final String relayUrl;
    private final String token;
    private final String localToken;
    private final String agentVersion;
    private final AndroidTools tools;
    private final LinkStore links;
    private final Set<HttpURLConnection> activePolls = ConcurrentHashMap.newKeySet();
    private final ReceivedJobs received = new ReceivedJobs(System::currentTimeMillis);
    private final Runnable onNetworkChange = this::abortActivePolls;
    private volatile String deviceId;
    private volatile boolean running = true;
    private IdleHook idleHook;
    private long lastKeepAliveMs;
    private volatile long lastHelloAt;
    /** Network generation at the last hello; a change re-announces this phone's addresses. */
    private volatile long helloEpoch = -1;
    private volatile long lastPollAt;
    private volatile boolean connected;
    private volatile String registeredName;
    private volatile String lastError;

    public DeviceNode(
            String relayUrl,
            AndroidTools tools,
            String token,
            String localToken,
            String agentVersion,
            LinkStore links,
            String deviceId) {
        String base = relayUrl == null ? "" : relayUrl.trim();
        this.relayUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.tools = tools;
        this.token = token;
        this.localToken = localToken == null ? "" : localToken;
        this.agentVersion = agentVersion == null ? "" : agentVersion;
        this.links = links;
        this.deviceId = deviceId == null ? "" : deviceId;
    }

    public void setIdleHook(IdleHook idleHook) {
        this.idleHook = idleHook;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId == null ? "" : deviceId;
    }

    public long lastHelloAt() {
        return lastHelloAt;
    }

    public long lastPollAt() {
        return lastPollAt;
    }

    public boolean connected() {
        return connected && running;
    }

    /** Push a filtered device event (notification trigger, battery threshold, share inbox) over the outbound relay link. */
    public void pushEvent(Map<String, Object> event) {
        if (event == null || relayUrl.isEmpty() || token == null || token.isEmpty()) return;
        Map<String, Object> body = new java.util.LinkedHashMap<>(event);
        body.putIfAbsent("time", System.currentTimeMillis());
        body.putIfAbsent("deviceId", deviceId);
        String json = Json.stringify(body);
        Thread t = new Thread(() -> {
            try {
                SimpleHttp.postJsonOnce(relayUrl + "/v1/device/events", json, 5_000, token);
            } catch (Exception e) {
                DaemonLog.print("event push failed: " + e.getMessage());
            }
        }, "backstage-event-push");
        t.setDaemon(true);
        t.start();
    }

    public String registeredName() {
        return registeredName;
    }

    public String lastError() {
        return lastError;
    }

    public String hello() throws IOException {
        Map<String, Object> identity = tools.identity();
        helloEpoch = com.deviceagent.shell.NetworkWatch.epoch();
        List<String> lan = LanAddresses.ipv4();
        String body = Json.stringify(Json.mapOf(
                "bootId", BOOT_ID,
                "model", identity.get("model"),
                "sdk", identity.get("sdk"),
                "agentVersion", agentVersion,
                "capabilities", Json.mapOf(
                        "shell", true, "install_packages", true, "input_injection", true,
                        "screenshots", true, "logcat", true, "intents", true, "clipboard", true,
                        "notifications", true, "settings", true, "virtual_display", true, "root", false),
                "lanAddresses", lan,
                "parallelJobs", PARALLEL_JOBS,
                "receipts", true));
        DaemonLog.print("hello → " + relayUrl + "/v1/device/hello lan=" + lan);
        String raw = SimpleHttp.postJsonOnce(relayUrl + "/v1/device/hello", body, HELLO_TIMEOUT_MS, token, activePolls);
        Map<String, Object> reply = Json.object(raw.isEmpty() ? "{}" : raw);
        String name = Json.string(reply, "name");
        if (name != null && !name.isBlank()) registeredName = name;
        lanes = reply.get("parallelJobs") instanceof Number granted ? Math.max(1, Math.min(PARALLEL_JOBS, granted.intValue())) : 1;
        lastHelloAt = System.currentTimeMillis();
        lastPollAt = lastHelloAt;
        connected = true;
        lastError = null;
        DaemonLog.print("hello ok name=" + registeredName + " pollMs=" + reply.get("pollMs"));
        return registeredName;
    }

    public void runForever() throws IOException {
        com.deviceagent.shell.NetworkWatch.addListener(onNetworkChange);
        int backoff = BACKOFF_MIN_MS;
        try {
            while (running) {
                try {
                    hello();
                    deliverAllPending();
                    backoff = BACKOFF_MIN_MS;
                    startLanes(generation.incrementAndGet());
                    while (running) {
                        if (helloEpoch != com.deviceagent.shell.NetworkWatch.epoch()) hello();
                        boolean hadJob = pollOnce();
                        backoff = BACKOFF_MIN_MS;
                        if (!hadJob) maybeKeepAlive();
                    }
                } catch (SimpleHttp.HttpException e) {
                    generation.incrementAndGet();
                    connected = false;
                    lastError = "HTTP " + e.status;
                    if (e.status == 401) {
                        DaemonLog.print("device token revoked; re-link in the app");
                        lastError = "device token revoked; re-link in the app";
                        sleep(BACKOFF_MAX_MS);
                    } else {
                        DaemonLog.print("relay HTTP " + e.status + ": " + trim(e.body));
                        backoff = waitBackoff(backoff);
                    }
                } catch (IOException e) {
                    generation.incrementAndGet();
                    if (!running) return;
                    connected = false;
                    lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    DaemonLog.print("relay poll failed: " + lastError + " (network " + com.deviceagent.shell.NetworkWatch.signature() + ")");
                    backoff = waitBackoff(backoff);
                } catch (RuntimeException e) {
                    generation.incrementAndGet();
                    if (!running) return;
                    connected = false;
                    lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + trim(e.getMessage()));
                    DaemonLog.print("relay poll error: " + lastError);
                    backoff = waitBackoff(backoff);
                }
            }
        } finally {
            com.deviceagent.shell.NetworkWatch.removeListener(onNetworkChange);
            abortActivePolls();
        }
    }

    /** Extra poll workers so jobs from different agents run side by side. Each stops when the connection is renewed. */
    private void startLanes(int gen) {
        for (int i = 1; i < lanes; i++) {
            Thread worker = new Thread(() -> {
                int backoff = BACKOFF_MIN_MS;
                while (running && generation.get() == gen) {
                    try {
                        pollOnce();
                        backoff = BACKOFF_MIN_MS;
                    } catch (IOException e) {
                        if (!running || generation.get() != gen) return;
                        backoff = waitBackoff(backoff);
                    } catch (RuntimeException e) {
                        DaemonLog.print("relay lane failed: " + e);
                        sleep(backoff);
                    }
                }
            }, "relay-lane-" + i);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void deliverAllPending() throws IOException {
        java.nio.file.Path dir = java.nio.file.Paths.get(links.statePath("pending-result.json")).getParent();
        deliverPending(java.nio.file.Paths.get(links.statePath("pending-result.json")));
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return;
        try (var files = java.nio.file.Files.newDirectoryStream(dir, "pending-result-*.json")) {
            for (java.nio.file.Path file : files) deliverPending(file);
        }
    }

    private void deliverPending(java.nio.file.Path file) throws IOException {
        if (!java.nio.file.Files.exists(file)) return;
        String body = new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        try {
            SimpleHttp.postJsonOnce(relayUrl + "/v1/device/result", body, RESULT_TIMEOUT_MS, token);
        } catch (SimpleHttp.HttpException e) {
            if (e.status == 400 || e.status == 413) {
                // Unrecoverable oversized or rejected payload: never poison-pill the queue forever.
                DaemonLog.print("dropping unsendable result (HTTP " + e.status + ") from " + file.getFileName());
                try {
                    String jobId = Json.string(Json.object(body), "id");
                    if (jobId != null && !jobId.isBlank()) {
                        String fallback = Json.stringify(Json.mapOf(
                                "id", jobId,
                                "error", Json.mapOf("message", "Result exceeded relay size limit (HTTP " + e.status + "). Narrow the command or output.")));
                        SimpleHttp.postJsonOnce(relayUrl + "/v1/device/result", fallback, RESULT_TIMEOUT_MS, token);
                    }
                } catch (Exception ignored) { }
            } else if (e.status != 404) {
                throw e;
            }
        }
        java.nio.file.Files.deleteIfExists(file);
    }

    /** Set when this process is about to be replaced; queued jobs then wait for the new daemon. */
    private static volatile boolean draining;
    public static void drain() { draining = true; }

    public boolean pollOnce() throws IOException {
        if (draining) { sleep(1_000); return false; }
        String raw = SimpleHttp.postJsonOnce(
                relayUrl + "/v1/device/next", Json.stringify(Json.mapOf("bootId", BOOT_ID)), NEXT_TIMEOUT_MS, token, activePolls);
        if (!running) throw new IOException("poll stopped before execution");
        lastPollAt = System.currentTimeMillis();
        connected = true;
        lastError = null;
        Map<String, Object> reply = Json.object(raw.isEmpty() ? "{}" : raw);
        if (Boolean.TRUE.equals(reply.get("idle"))) {
            // The relay answers these at once; polling again immediately would spin.
            if (Boolean.TRUE.equals(reply.get("duplicate")) || Boolean.TRUE.equals(reply.get("stale"))) sleep(BACKOFF_MIN_MS);
            return false;
        }
        String pendingId = Json.string(reply, "id");
        Object envelope = reply.get("envelope");
        DaemonLog.print("job id=" + pendingId + " envelope=" + (envelope != null));
        boolean validId = pendingId != null && pendingId.matches("[A-Za-z0-9_-]{1,128}");
        String safeId = validId ? pendingId : java.util.UUID.randomUUID().toString();
        java.nio.file.Path pending = java.nio.file.Paths.get(links.statePath("pending-result-" + safeId + ".json"));
        if (validId) {
            ack(pendingId);
            ReceivedJobs.Seen seen = received.receive(pendingId);
            if (seen != ReceivedJobs.Seen.NEW) {
                // The relay sent it again because a receipt was lost. Never run it twice.
                DaemonLog.print("job id=" + pendingId + " already " + seen);
                if (seen == ReceivedJobs.Seen.DONE) deliverPending(pending);
                return true;
            }
        }
        try {
            EnvelopeCall.Outcome outcome = EnvelopeCall.handle(links, deviceId, envelope, tools);
            com.deviceagent.crypto.PrivateState.write(pending, Json.stringify(outcome.relayResult(pendingId)));
        } finally {
            if (validId) received.done(pendingId);
        }
        deliverPending(pending);
        return true;
    }

    /** Tells the relay this job arrived, so it does not send it again. Older relays answer 404. */
    private void ack(String jobId) {
        String json = Json.stringify(Json.mapOf("id", jobId));
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    SimpleHttp.postJsonOnce(relayUrl + "/v1/device/ack", json, ACK_TIMEOUT_MS, token);
                    return;
                } catch (SimpleHttp.HttpException e) {
                    return;
                } catch (IOException e) {
                    DaemonLog.print("ack failed id=" + jobId + ": " + e.getMessage());
                }
            }
        }, "backstage-job-ack");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        connected = false;
        com.deviceagent.shell.NetworkWatch.removeListener(onNetworkChange);
        abortActivePolls();
    }

    private void abortActivePolls() {
        for (HttpURLConnection conn : activePolls) {
            try {
                conn.disconnect();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void maybeKeepAlive() {
        if (idleHook == null) return;
        long now = System.currentTimeMillis();
        if (lastKeepAliveMs != 0L && now - lastKeepAliveMs < KEEP_ALIVE_EVERY_MS) return;
        runIdleHook();
    }

    private void runIdleHook() {
        if (idleHook == null) return;
        lastKeepAliveMs = System.currentTimeMillis();
        try {
            idleHook.onIdle();
        } catch (RuntimeException ignored) {
        }
    }

    /** Wait out a failure; a network change ends the wait and restarts the backoff. */
    private static int waitBackoff(int backoff) {
        long epoch = com.deviceagent.shell.NetworkWatch.epoch();
        sleep(backoff);
        return epoch != com.deviceagent.shell.NetworkWatch.epoch() ? BACKOFF_MIN_MS : nextBackoff(backoff);
    }

    private static int nextBackoff(int current) {
        long next = Math.min((long) current * 2L, BACKOFF_MAX_MS);
        return (int) next;
    }

    private static void sleep(int ms) {
        com.deviceagent.shell.NetworkWatch.sleep(ms);
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 180 ? t.substring(0, 180) + "…" : t;
    }
}
