package com.deviceagent.rpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class SimpleHttp {
    private SimpleHttp() {}

    public static final class HttpException extends IOException {
        public final int status;
        public final String body;

        public HttpException(int status, String body) {
            super("HTTP " + status + ": " + (body == null ? "" : body));
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    public static String get(String url, int timeoutMs, String bearerToken) throws IOException {
        return request("GET", url, null, timeoutMs, bearerToken, 2);
    }

    public static String postJson(String url, String json, int timeoutMs, String bearerToken) throws IOException {
        return request("POST", url, json, timeoutMs, bearerToken, 3);
    }

    public static String putJson(String url, String json, int timeoutMs, String bearerToken) throws IOException {
        return request("PUT", url, json, timeoutMs, bearerToken, 2);
    }

    public static String putJsonOnce(String url, String json, int timeoutMs, String bearerToken) throws IOException {
        return request("PUT", url, json, timeoutMs, bearerToken, 1);
    }

    public static String getOnce(String url, int timeoutMs, String bearerToken) throws IOException {
        return request("GET", url, null, timeoutMs, bearerToken, 1);
    }

    /** Single attempt — used for long-poll {@code /next} so we do not stack 40 s waits. */
    public static String postJsonOnce(String url, String json, int timeoutMs, String bearerToken) throws IOException {
        return requestInternal("POST", url, json, timeoutMs, bearerToken, 1, true, null);
    }

    /** Single attempt that tracks its open connection so callers can abort stalled long-polls immediately. */
    public static String postJsonOnce(
            String url,
            String json,
            int timeoutMs,
            String bearerToken,
            Set<HttpURLConnection> activeConns)
            throws IOException {
        return requestInternal("POST", url, json, timeoutMs, bearerToken, 1, true, activeConns);
    }

    public static String request(
            String method, String url, String json, int timeoutMs, String bearerToken, int attempts)
            throws IOException {
        return requestInternal(method, url, json, timeoutMs, bearerToken, attempts, true, null);
    }

    /** Pairing credentials belong only to the server the user approved. */
    public static String requestNoRedirect(String method, String url, String json, int timeoutMs, String bearerToken)
            throws IOException {
        return requestInternal(method, url, json, timeoutMs, bearerToken, 1, false, null);
    }

    private static String requestInternal(
            String method,
            String url,
            String json,
            int timeoutMs,
            String bearerToken,
            int attempts,
            boolean followRedirects,
            Set<HttpURLConnection> activeConns)
            throws IOException {
        IOException last = null;
        int max = Math.max(1, attempts);
        for (int attempt = 0; attempt < max; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                if (activeConns != null) activeConns.add(conn);
                conn.setRequestMethod(method);
                conn.setConnectTimeout(Math.min(8000, Math.max(1000, timeoutMs)));
                conn.setReadTimeout(timeoutMs);
                conn.setUseCaches(false);
                boolean allowRedirects = followRedirects && (bearerToken == null || bearerToken.isBlank());
                conn.setInstanceFollowRedirects(allowRedirects);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Connection", "close");
                if (bearerToken != null && !bearerToken.isBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
                }
                if (json != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(bytes);
                    }
                }
                int code = conn.getResponseCode();
                InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = "";
                if (in != null) {
                    try (InputStream stream = in) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int count;
                        while ((count = stream.read(chunk)) != -1) buf.write(chunk, 0, count);
                        body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
                if (!followRedirects && code >= 300 && code < 400) throw new HttpException(code, body);
                if (code == 401 || code == 403) {
                    throw new HttpException(code, body);
                }
                if (code >= 400) {
                    throw new HttpException(code, body);
                }
                return body;
            } catch (HttpException e) {
                if (e.status == 401 || e.status == 403 || e.status == 404 || attempt == max - 1) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                last = e;
                if (attempt == max - 1) break;
            } finally {
                if (activeConns != null && conn != null) activeConns.remove(conn);
                if (conn != null) conn.disconnect();
            }
            try {
                Thread.sleep(200L * (attempt + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw last != null ? last : e(ie);
            }
        }
        throw last != null ? last : new IOException("HTTP " + method + " failed: " + url);
    }

    private static IOException e(InterruptedException ie) {
        IOException io = new IOException("interrupted");
        io.initCause(ie);
        return io;
    }
}
