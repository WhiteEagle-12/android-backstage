package com.deviceagent.rpc;

import com.deviceagent.crypto.Envelope;
import com.deviceagent.crypto.LinkStore;
import com.deviceagent.tools.AndroidTools;
import com.deviceagent.tools.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open a call envelope, run {@link AndroidTools#call}, seal {@code {id, result}}
 * or {@code {id, error: {message}}}. Crypto refusals are not sealed.
 */
public final class EnvelopeCall {
    private EnvelopeCall() {}

    public static final class Outcome {
        public final Envelope envelope;
        public final String error;
        public final String callId;

        private Outcome(Envelope envelope, String error, String callId) {
            this.envelope = envelope;
            this.error = error;
            this.callId = callId;
        }

        public boolean ok() {
            return envelope != null;
        }

        public Map<String, Object> relayResult(String jobId) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", jobId);
            if (envelope != null) {
                m.put("envelope", envelope.toMap());
            } else {
                m.put("error", Json.mapOf("message", error));
            }
            return m;
        }

        public Map<String, Object> localResult() {
            if (envelope != null) {
                return Json.mapOf("envelope", envelope.toMap());
            }
            return Json.mapOf("error", Json.mapOf("message", error));
        }
    }

    private static final java.util.Set<String> READ_ONLY_TOOLS = java.util.Set.of(
            "android.observe", "android.wait", "android.packages", "android.devices",
            "android.logs", "android.task_inspect", "android.task_list", "android.pull", "android.location");

    private static boolean isReadOnlyCall(String name, Map<String, Object> args) {
        if (args.containsKey("requestId")) return false;
        if (READ_ONLY_TOOLS.contains(name)) return true;
        if ("android.task".equals(name)) {
            Object op = args.get("operation");
            return "list".equals(op) || "inspect".equals(op);
        }
        if ("android.file".equals(name)) return "pull".equals(args.get("operation"));
        if ("android.device".equals(name)) {
            Object op = args.get("operation");
            if ("location".equals(op)) return true;
            if ("clipboard".equals(op)) return !args.containsKey("text");
            if ("media".equals(op) || "control".equals(op) || "audio".equals(op)) return "status".equals(args.get("action"));
            return false;
        }
        if ("android.clipboard".equals(name)) return !args.containsKey("text");
        if ("android.notification".equals(name)) return "list".equals(args.get("operation"));
        if ("android.device_control".equals(name)) return "status".equals(args.get("operation"));
        if ("android.media".equals(name)) return "status".equals(args.get("operation"));
        if ("android.audio".equals(name)) return "status".equals(args.get("operation"));
        return false;
    }

    private static final java.util.concurrent.ExecutorService WORKERS =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "backstage-call");
                t.setDaemon(true);
                return t;
            });
    /** How long past its deadline a call may run before the lane answers without it. */
    static final long LATE_GRACE_MS = 2_000;

    /**
     * Run a tool on a worker so the poll lane answers when the deadline passes, even if the
     * tool is stuck in a system call. The stuck worker's stack goes to the daemon log; its
     * late result is dropped.
     */
    static Object runBounded(java.util.function.Supplier<Object> work, long expiry, String name, boolean readOnly) {
        var runner = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var task = new java.util.concurrent.FutureTask<Object>(work::get);
        WORKERS.execute(() -> { runner.set(Thread.currentThread()); try { task.run(); } finally { runner.set(null); Thread.interrupted(); } });
        long wait = Math.max(0, expiry - System.currentTimeMillis()) + LATE_GRACE_MS;
        try {
            return task.get(wait, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Thread stuck = runner.get();
            StringBuilder trace = new StringBuilder("call ").append(name).append(" still running ").append(LATE_GRACE_MS).append(" ms past its deadline; answering without it");
            if (stuck != null) for (var f : stuck.getStackTrace()) trace.append("\n  at ").append(f);
            com.deviceagent.daemon.DaemonLog.print(trace.toString());
            task.cancel(true);
            return readOnly
                    ? Json.mapOf("outcome", "failed", "code", "device_timeout", "retryable", true, "inputDelivered", false, "error", "The phone did not finish this read in time. Try again.")
                    : Json.mapOf("outcome", "unknown", "code", "device_timeout", "retryable", true, "inputDelivered", null, "error", "The phone did not finish before the request expired. Read the current state before you retry.");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            if (e.getCause() instanceof Error err) throw err;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new IllegalStateException("interrupted");
        }
    }

    public static Outcome handle(
            LinkStore store, String deviceId, Object envelopeObj, AndroidTools tools) {
        long receivedAt = System.nanoTime();
        Envelope env;
        try {
            env = Envelope.parse(envelopeObj);
        } catch (RuntimeException e) {
            return refuse("malformed envelope", null);
        }
        if (env.v != 2) return refuse("protocol upgrade required; v2 deadline envelopes only", null);
        byte[] plaintext;
        try {
            plaintext = store.openCall(env);
        } catch (LinkStore.Unauthorized e) {
            return refuse(e.getMessage(), null);
        }
        Map<String, Object> call;
        try {
            call = Json.object(new String(plaintext, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return sealedError(store, env.from, deviceId, null, "malformed call plaintext");
        }
        Object id = call.get("id");
        if (deviceId == null || deviceId.isBlank() || !deviceId.equals(call.get("deviceId")))
            return sealedError(store, env.from, deviceId, id, "target device ID missing or mismatched; no action executed");
        Object deadline = call.get("expiresAt");
        long expiry = deadline instanceof Number ? ((Number)deadline).longValue() : 0;
        if (expiry <= System.currentTimeMillis() || expiry > System.currentTimeMillis() + 660_000L)
            return sealedError(store, env.from, deviceId, id, "call expired or invalid deadline");
        String name = Json.string(call, "name");
        String subOwner = Json.string(call, "subOwner");
        String owner = (subOwner != null && !subOwner.isBlank() && subOwner.length() <= 128)
                ? env.from + ":" + subOwner : env.from;
        Object rawArgs = call.get("arguments");
        Map<String, Object> args = rawArgs instanceof Map ? (Map<String, Object>) rawArgs : Map.of();
        try {
            Map<String,Object> response;
            // Calls from different agents run concurrently. Tools order work on a
            // shared display themselves; there is no device-wide lock here.
            {
                if (expiry <= System.currentTimeMillis()) return sealedError(store, env.from, deviceId, id, "call expired waiting for device lease");
                store.permits.authorize(env.from, name, args);
                boolean readOnly = isReadOnlyCall(name, args);
                java.util.function.Supplier<Object> runTool = () -> runBounded(() -> {
                    long queueMs=(System.nanoTime()-receivedAt)/1_000_000;
                    try(var deadlineScope=com.deviceagent.tools.ActionDeadline.until(expiry)){
                    Map<String,Object> result=tools.callOwned(name,args,owner);
                    Map<String,Object> timing=new LinkedHashMap<>();if(result.get("timing") instanceof Map<?,?> m)m.forEach((k,v)->timing.put(k.toString(),v));
                    timing.put("deviceQueueMs",queueMs+(timing.get("displayWaitMs") instanceof Number w?w.longValue():0));
                    result.put("timing",timing);
                    return result;
                    }
                }, expiry, name, readOnly);
                if (readOnly) {
                    try { response = Json.mapOf("result", runTool.get()); }
                    catch (RuntimeException e) { response = Json.mapOf("error", Json.mapOf("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
                } else {
                    response = store.journal.execute(owner, args.get("requestId") instanceof String requestId ? requestId : id instanceof String ? (String)id : null, name, args, runTool);
                }
            }
            response = new LinkedHashMap<>(response);
            response.put("id",id);
            byte[] out = Json.stringify(response).getBytes(StandardCharsets.UTF_8);
            Envelope sealed = store.sealResult(env.from, deviceId, out);
            return new Outcome(sealed, null, id == null ? null : String.valueOf(id));
        } catch (LinkStore.Unauthorized e) {
            return refuse(e.getMessage(), id);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return sealedError(store, env.from, deviceId, id, msg);
        }
    }

    private static Outcome sealedError(
            LinkStore store, String agentId, String deviceId, Object id, String message) {
        try {
            byte[] out = Json.stringify(mapId(id, "error", Json.mapOf("message", message)))
                    .getBytes(StandardCharsets.UTF_8);
            Envelope sealed = store.sealResult(agentId, deviceId, out);
            return new Outcome(sealed, null, id == null ? null : String.valueOf(id));
        } catch (LinkStore.Unauthorized e) {
            return refuse(e.getMessage(), id);
        } catch (RuntimeException e) {
            return refuse("bad tag", id);
        }
    }

    private static Outcome refuse(String reason, Object id) {
        String msg = reason != null && reason.startsWith("unauthorized:")
                ? reason
                : "unauthorized: " + (reason == null ? "unknown" : reason);
        return new Outcome(null, msg, id == null ? null : String.valueOf(id));
    }

    private static Map<String, Object> mapId(Object id, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put(key, value);
        return m;
    }
}
