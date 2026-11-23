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

    public static Outcome handle(
            LinkStore store, String deviceId, Object envelopeObj, AndroidTools tools) {
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
        Object rawArgs = call.get("arguments");
        Map<String, Object> args = rawArgs instanceof Map ? (Map<String, Object>) rawArgs : Map.of();
        try {
            Map<String,Object> response;
            long queuedAt=System.nanoTime();
            // Calls from different agents run concurrently. Tools order work on a
            // shared display themselves; there is no device-wide lock here.
            {
                if (expiry <= System.currentTimeMillis()) return sealedError(store, env.from, deviceId, id, "call expired waiting for device lease");
                store.permits.authorize(env.from, name, args);
                long queueMs=(System.nanoTime()-queuedAt)/1_000_000;
                java.util.function.Supplier<Object> runTool = () -> {
                    try(var deadlineScope=com.deviceagent.tools.ActionDeadline.until(expiry)){
                    Map<String,Object> result=tools.callOwned(name,args,env.from);
                    if(Boolean.TRUE.equals(args.get("timing"))||Boolean.TRUE.equals(args.get("diagnostics"))){Map<String,Object> timing=new LinkedHashMap<>();if(result.get("timing") instanceof Map<?,?> m)m.forEach((k,v)->timing.put(k.toString(),v));timing.put("deviceQueueMs",queueMs);result.put("timing",timing);}
                    return result;
                    }
                };
                if (isReadOnlyCall(name, args)) {
                    try { response = Json.mapOf("result", runTool.get()); }
                    catch (RuntimeException e) { response = Json.mapOf("error", Json.mapOf("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
                } else {
                    response = store.journal.execute(env.from, args.get("requestId") instanceof String requestId ? requestId : id instanceof String ? (String)id : null, name, args, runTool);
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
