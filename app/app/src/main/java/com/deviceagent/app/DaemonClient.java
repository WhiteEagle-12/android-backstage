package com.deviceagent.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app's calls to its own daemon on 127.0.0.1, run off the main thread with results posted back. */
final class DaemonClient {
    interface Result<T> { void done(T value, Exception error); }

    /** A raw response, for the preview endpoint that returns image bytes + frame metadata. */
    static final class Bytes {
        int status;
        long frameSeq;
        int sourceWidth, sourceHeight;
        byte[] body = new byte[0];
        android.graphics.Bitmap bitmap;
    }

    private final Context app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService previewPool = Executors.newSingleThreadExecutor();
    private final ExecutorService inputPool = Executors.newSingleThreadExecutor();

    DaemonClient(Context context) { app = context.getApplicationContext(); }

    private String url(AgentConfig c, String path) { return "http://127.0.0.1:" + c.daemonPort + path; }

    void get(String path, Result<Map<String, Object>> result) {
        pool.execute(() -> {
            Map<String, Object> value = null; Exception error = null;
            try {
                AgentConfig c = AgentConfig.load(app);
                String auth = com.deviceagent.crypto.LocalAuth.requestHeader(c.localToken, "GET", path, "");
                value = Json.object(SimpleHttp.requestOnceWithHeaders("GET", url(c, path), null, 2500, Map.of("X-Backstage-Auth", auth)));
            } catch (Exception e) { error = e; }
            post(result, value, error);
        });
    }

    void post(String path, Map<String, Object> body, Result<Map<String, Object>> result) {
        postOn(pool, path, body, result);
    }

    /** Ordered input/takeover requests so takeover lock always lands before the first touch. */
    void postOrdered(String path, Map<String, Object> body, Result<Map<String, Object>> result) {
        postOn(inputPool, path, body, result);
    }

    private void postOn(ExecutorService exec, String path, Map<String, Object> body, Result<Map<String, Object>> result) {
        exec.execute(() -> {
            Map<String, Object> value = null; Exception error = null;
            try {
                AgentConfig c = AgentConfig.load(app);
                String payload = Json.stringify(body == null ? Map.of() : body);
                String auth = com.deviceagent.crypto.LocalAuth.requestHeader(c.localToken, "POST", path, payload);
                String raw = SimpleHttp.requestOnceWithHeaders("POST", url(c, path), payload, 6000, Map.of("X-Backstage-Auth", auth));
                value = raw == null || raw.isBlank() ? Map.of() : Json.object(raw);
            } catch (Exception e) { error = e; }
            post(result, value, error);
        });
    }

    void bytes(String path, Result<Bytes> result) {
        previewPool.execute(() -> {
            Bytes out = new Bytes(); Exception error = null;
            HttpURLConnection conn = null;
            try {
                AgentConfig c = AgentConfig.load(app);
                conn = (HttpURLConnection) new URL(url(c, path)).openConnection();
                conn.setConnectTimeout(1500); conn.setReadTimeout(3000);
                conn.setRequestProperty("X-Backstage-Auth", com.deviceagent.crypto.LocalAuth.requestHeader(c.localToken, "GET", path, ""));
                out.status = conn.getResponseCode();
                out.frameSeq = parseLongHeader(conn.getHeaderField("X-Frame-Seq"));
                out.sourceWidth = (int) parseLongHeader(conn.getHeaderField("X-Source-Width"));
                out.sourceHeight = (int) parseLongHeader(conn.getHeaderField("X-Source-Height"));
                if (out.status == 200) {
                    try (InputStream in = conn.getInputStream()) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] chunk = new byte[16384]; int n;
                        while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
                        out.body = buffer.toByteArray();
                        if (out.body.length > 0) {
                            out.bitmap = android.graphics.BitmapFactory.decodeByteArray(out.body, 0, out.body.length);
                        }
                    }
                }
            } catch (Exception e) { error = e; }
            finally { if (conn != null) conn.disconnect(); }
            post(result, out, error);
        });
    }

    private static long parseLongHeader(String v) {
        if (v == null || v.isBlank()) return 0L;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 0L; }
    }

    private <T> void post(Result<T> result, T value, Exception error) {
        if (result != null) ui.post(() -> result.done(value, error));
    }

    /** The list stored under key in a daemon reply, as maps. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> reply, String key) {
        java.util.ArrayList<Map<String, Object>> out = new java.util.ArrayList<>();
        Object list = reply == null ? null : reply.get(key);
        if (list instanceof List) for (Object o : (List<?>) list) if (o instanceof Map) out.add((Map<String, Object>) o);
        return out;
    }

    static List<String> strings(Map<String, Object> reply, String key) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        Object list = reply == null ? null : reply.get(key);
        if (list instanceof List) for (Object o : (List<?>) list) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static long num(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    void shutdown() { pool.shutdownNow(); previewPool.shutdownNow(); inputPool.shutdownNow(); }
}
