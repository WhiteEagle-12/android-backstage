package com.deviceagent.app;

import com.deviceagent.crypto.LinkPairing;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;

/** Setup decisions shared by the screen and host tests. No Android dependencies. */
public final class SetupRules {
    public static final String DEFAULT_RELAY = "https://relay.android-backstage.com";
    private SetupRules() {}

    public static final class SetupInput {
        public final String server;
        public final String code;
        public final String clientName;

        public SetupInput(String server, String code, String clientName) {
            this.server = server;
            this.code = code;
            this.clientName = clientName;
        }
    }

    public static String connectionName(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty() || value.length() > 60 || value.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new IllegalArgumentException("Use a name from 1 to 60 characters.");
        return value;
    }

    public static String relayUrl(String input) {
        String value = input == null ? "" : input.trim();
        if (!value.contains("://")) value = "https://" + value;
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (path != null && !path.isEmpty() && !path.equals("/"))
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return "https://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() < 0 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Use a secure server address, such as https://relay.example.com.");
        }
    }

    /**
     * Parses either a bare connection code (e.g. {@code ABCD-EFGHJK}) or a full
     * {@code androidbackstage://connect?relay=...&code=...&name=...} setup link.
     */
    public static SetupInput parseSetupInput(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty()) return null;
        int schemeIdx = text.toLowerCase(Locale.ROOT).indexOf("androidbackstage://connect");
        if (schemeIdx >= 0) {
            String candidate = text.substring(schemeIdx).split("\\s+")[0];
            try {
                URI uri = URI.create(candidate);
                if (!"androidbackstage".equalsIgnoreCase(uri.getScheme())
                        || !"connect".equalsIgnoreCase(uri.getHost())) {
                    return null;
                }
                String relayParam = queryParam(uri.getRawQuery(), "relay");
                String codeParam = queryParam(uri.getRawQuery(), "code");
                String nameParam = queryParam(uri.getRawQuery(), "name");
                if (codeParam == null || codeParam.isBlank()) return null;
                String server = relayParam == null || relayParam.isBlank() ? "" : relayUrl(relayParam);
                String code = LinkPairing.normalizeCode(codeParam);
                String name = nameParam == null || nameParam.isBlank() ? "" : connectionName(nameParam);
                return new SetupInput(server, code, name);
            } catch (RuntimeException e) {
                return null;
            }
        }
        try {
            return new SetupInput("", LinkPairing.normalizeCode(text), "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Accepts either a plain port ({@code 39821}) or {@code host:port} ({@code 192.168.1.5:39821}). */
    public static int parseHostPort(String raw, int fallback) {
        if (raw == null) return fallback;
        String text = raw.trim();
        if (text.isEmpty()) return fallback;
        int colon = text.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < text.length()) {
            text = text.substring(colon + 1).trim();
        }
        try {
            int port = Integer.parseInt(text);
            return (port > 0 && port < 65536) ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Extracts a 6-digit Android Wireless debugging pairing code, ignoring spaces or dashes. */
    public static String normalizePairingDigits(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[\\s-]+", "");
        return cleaned.matches("\\d{6}") ? cleaned : "";
    }

    /** Classifies local wireless-ADB errors so the UI can show the right recovery step. */
    public static String classifyAdbError(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof ConnectException) return "refused";
            if (cur instanceof SocketTimeoutException) return "timeout";
            if (cur instanceof SSLException) return "auth_revoked";
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("refused")) return "refused";
                if (lower.contains("timed out") || lower.contains("timeout")) return "timeout";
                if (lower.contains("certificate") || lower.contains("handshake")
                        || lower.contains("bad_certificate") || lower.contains("did not offer tls")
                        || lower.contains("did not accept the paired key")
                        || lower.contains("eof") || lower.contains("connection reset")) {
                    return "auth_revoked";
                }
            }
            if (cur instanceof java.io.EOFException) return "auth_revoked";
            cur = cur.getCause();
        }
        return "error";
    }

    public static boolean isRelayAuthError(String lastError) {
        if (lastError == null || lastError.isBlank()) return false;
        String lower = lastError.toLowerCase(Locale.ROOT);
        return lower.contains("revoked") || lower.contains("http 401");
    }

    public static boolean sameServer(String first, String second) {
        try { return relayUrl(first).equals(relayUrl(second)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    public static boolean shouldStart(boolean enabled, boolean linked, boolean paired) {
        return enabled && linked && paired;
    }

    /** Single smart label for the wireless-debugging fix button. No Android dependencies. */
    public static String accessButtonLabel(boolean devOptionsOn, boolean wirelessOn, boolean paired) {
        if (!devOptionsOn) return "Open About phone (enable Developer options)";
        if (!wirelessOn || !paired) return "Open Wireless debugging";
        return "Open Android settings";
    }

    public static boolean isRiskyUsbConfig(String functions) {
        if (functions == null || functions.isBlank()) return false;
        String lower = functions.toLowerCase(java.util.Locale.ROOT);
        for (String part : lower.split(",")) {
            String trimmed = part.trim();
            if ("rndis".equals(trimmed) || "ncm".equals(trimmed)) return true;
        }
        return false;
    }

    public static boolean isCurrentDaemon(Map<String, Object> health) {
        return Boolean.TRUE.equals(health.get("ok")) && "backstage".equals(health.get("service"))
                && BuildInfo.VERSION.equals(health.get("version"));
    }

    public static boolean relayConnected(Map<String, Object> health) {
        Object relay = health.get("relay");
        return isCurrentDaemon(health) && relay instanceof Map
                && Boolean.TRUE.equals(((Map<?, ?>) relay).get("connected"));
    }

    public static String linkError(Exception error) {
        if (error instanceof com.deviceagent.rpc.SimpleHttp.HttpException) {
            int status = ((com.deviceagent.rpc.SimpleHttp.HttpException) error).status;
            if (status == 404 || status == 409 || status == 410 || status == 401 || status == 403)
                return "That code is expired or already used. Ask your assistant for a new code, then try again.";
        }
        if (error instanceof java.io.IOException)
            return "Couldn’t reach your assistant. Check your internet connection and server address, then try a new code.";
        return "The connection could not be verified. Ask your assistant for a new code and try again.";
    }

    private static String queryParam(String rawQuery, String wanted) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (!wanted.equals(key)) continue;
            String val = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                return URLDecoder.decode(val, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }
}
