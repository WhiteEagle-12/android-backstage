package com.deviceagent.daemon;

import com.deviceagent.app.BuildInfo;
import com.deviceagent.tools.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk {@code daemon.json}. Shared by {@link Main} (reader) and {@link Starter} (writer).
 */
public final class DaemonConfig {
    public static final String DEFAULT_PATH = "/data/local/tmp/backstage/daemon.json";

    public String relayUrl = "";
    public String deviceToken = "";
    public String localToken = "";
    public String deviceName = "";
    public String deviceId = "";
    public String agentVersion = BuildInfo.VERSION;
    public String apkPath = "";
    public String packageName = BuildInfo.DEFAULT_PACKAGE;
    /** Inbound HTTP port (app supervision; optional LAN envelopes when allowLanDirect is enabled). Default 18422. */
    public int port = 18422;
    /** Opt-in LAN/tailnet listener bind (0.0.0.0); false binds strictly to 127.0.0.1. */
    public boolean allowLanDirect = false;
    /** Opt-in: keep global USB debugging enabled so adbd survives Wi-Fi transitions. */
    public boolean keepUsbDebugging = false;
    /** Opt-in: allow raw android.shell commands on this device (bypasses blocked apps). */
    public boolean allowShell = false;
    /** Opt-in: allow agent tasks to run while the phone's physical screen is off or locked. */
    public boolean allowScreenOff = false;
    /** Absolute path of the json file this was loaded from (or will be written to). */
    public String configPath = DEFAULT_PATH;
    /** Linked agents: {@code [{agentId, linkKey, lastCallCounter, resultCounter}]}. */
    public List<Link> links = new ArrayList<>();
    public List<String> revokedLinks = new ArrayList<>();
    /** Packages blocked on-device by the privacy fence. */
    public List<String> blockedPackages = new ArrayList<>();
    /** Event trigger rules pushed to the relay (notification filters, battery threshold). */
    public Map<String, Object> eventRules = new LinkedHashMap<>();

    public static final class Link {
        public String agentId = "";
        public String linkKey = "";
        public String lastCallCounter = "0";
        public String resultCounter = "0";
    }

    public static DaemonConfig load(String path) throws IOException {
        Path file = java.nio.file.Paths.get(path);
        byte[] raw = Files.readAllBytes(file);
        String text = new String(raw, StandardCharsets.UTF_8);
        Map<String, Object> obj = Json.object(text);
        DaemonConfig c = new DaemonConfig();
        c.configPath = file.toAbsolutePath().toString();
        c.relayUrl = str(obj, "relayUrl");
        c.deviceToken = str(obj, "deviceToken");
        c.localToken = str(obj, "localToken");
        c.deviceName = str(obj, "deviceName");
        c.deviceId = str(obj, "deviceId");
        String ver = str(obj, "agentVersion");
        c.agentVersion = ver.isEmpty() ? BuildInfo.VERSION : ver;
        c.apkPath = str(obj, "apkPath");
        String pkg = str(obj, "packageName");
        c.packageName = pkg.isEmpty() ? BuildInfo.DEFAULT_PACKAGE : pkg;
        Object port = obj.get("port");
        if (port instanceof Number) {
            c.port = ((Number) port).intValue();
        } else if (port != null && !String.valueOf(port).trim().isEmpty()) {
            try {
                c.port = Integer.parseInt(String.valueOf(port).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (c.port <= 0 || c.port >= 65536) c.port = 18422;
        c.allowLanDirect = Boolean.TRUE.equals(obj.get("allowLanDirect"));
        c.keepUsbDebugging = Boolean.TRUE.equals(obj.get("keepUsbDebugging"));
        c.allowShell = Boolean.TRUE.equals(obj.get("allowShell"));
        c.allowScreenOff = Boolean.TRUE.equals(obj.get("allowScreenOff"));
        c.links = parseLinks(obj.get("links"));
        if (obj.get("revokedLinks") instanceof List) for (Object x : (List<?>)obj.get("revokedLinks")) c.revokedLinks.add(String.valueOf(x));
        if (obj.get("blockedPackages") instanceof List) for (Object x : (List<?>)obj.get("blockedPackages")) c.blockedPackages.add(String.valueOf(x));
        if (obj.get("eventRules") instanceof Map<?, ?> rules) rules.forEach((k, v) -> c.eventRules.put(String.valueOf(k), v));
        c.links.removeIf(l -> c.revokedLinks.contains(fingerprint(l.agentId, l.linkKey)));
        return c;
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relayUrl", relayUrl == null ? "" : relayUrl);
        m.put("deviceToken", deviceToken == null ? "" : deviceToken);
        m.put("localToken", localToken == null ? "" : localToken);
        m.put("deviceName", deviceName == null ? "" : deviceName);
        m.put("deviceId", deviceId == null ? "" : deviceId);
        m.put("agentVersion", agentVersion == null || agentVersion.isEmpty() ? BuildInfo.VERSION : agentVersion);
        m.put("apkPath", apkPath == null ? "" : apkPath);
        m.put("packageName", packageName == null || packageName.isEmpty() ? BuildInfo.DEFAULT_PACKAGE : packageName);
        if (port != 18422) m.put("port", port);
        if (allowLanDirect) m.put("allowLanDirect", true);
        if (keepUsbDebugging) m.put("keepUsbDebugging", true);
        if (allowShell) m.put("allowShell", true);
        if (allowScreenOff) m.put("allowScreenOff", true);
        List<Map<String, Object>> linkRows = new ArrayList<>();
        if (links != null) {
            for (Link link : links) {
                if (link == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("agentId", link.agentId == null ? "" : link.agentId);
                row.put("linkKey", link.linkKey == null ? "" : link.linkKey);
                row.put("lastCallCounter", link.lastCallCounter == null ? "0" : link.lastCallCounter);
                row.put("resultCounter", link.resultCounter == null ? "0" : link.resultCounter);
                linkRows.add(row);
            }
        }
        m.put("links", linkRows);
        m.put("revokedLinks", revokedLinks);
        if (blockedPackages != null && !blockedPackages.isEmpty()) m.put("blockedPackages", blockedPackages);
        if (eventRules != null && !eventRules.isEmpty()) m.put("eventRules", eventRules);
        return Json.stringify(m);
    }

    public synchronized void save() throws IOException {
        if (configPath == null || configPath.isBlank()) {
            configPath = DEFAULT_PATH;
        }
        Path file = java.nio.file.Paths.get(configPath);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = java.nio.file.Paths.get(configPath + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(tmp,
                java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap((toJson() + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (parent != null) try (java.nio.channels.FileChannel dir = java.nio.channels.FileChannel.open(parent,
                java.nio.file.StandardOpenOption.READ)) { dir.force(true); }
    }

    public static String fingerprint(String agent, String key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest((agent + ":" + key).getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public boolean hasRelay() {
        return relayUrl != null && !relayUrl.isBlank() && deviceToken != null && !deviceToken.isBlank();
    }

    /**
     * If {@code daemon.json.prev} exists (written by {@link Starter} before replacing
     * the file), take the higher call/result counters for matching agent+key so a
     * supervisor restart cannot reopen a replay window.
     */
    public boolean mergePrevCounters() throws IOException {
        if (configPath == null || configPath.isBlank()) return false;
        Path prev = java.nio.file.Paths.get(configPath + ".prev");
        if (!Files.isRegularFile(prev)) return false;
        try {
            DaemonConfig old = load(prev.toString());
            boolean changed = false;
            // The app rewrites this file from its own preferences when it (re)starts the
            // daemon. Anything the daemon learned at runtime (/configure from the CLI,
            // a relay link, extra agents) must survive that when the app has nothing newer.
            if (isBlank(relayUrl) && !isBlank(old.relayUrl)) { relayUrl = old.relayUrl; changed = true; }
            if (isBlank(deviceToken) && !isBlank(old.deviceToken)) { deviceToken = old.deviceToken; changed = true; }
            if (isBlank(deviceName) && !isBlank(old.deviceName)) { deviceName = old.deviceName; changed = true; }
            if (isBlank(deviceId) && !isBlank(old.deviceId)) { deviceId = old.deviceId; changed = true; }
            for (String revoked : old.revokedLinks) if (!revokedLinks.contains(revoked)) { revokedLinks.add(revoked); changed = true; }
            if (blockedPackages.isEmpty() && !old.blockedPackages.isEmpty()) { blockedPackages.addAll(old.blockedPackages); changed = true; }
            if (eventRules.isEmpty() && !old.eventRules.isEmpty()) { eventRules.putAll(old.eventRules); changed = true; }
            if (links.removeIf(l -> revokedLinks.contains(fingerprint(l.agentId, l.linkKey)))) changed = true;
            if (links != null && old.links != null) {
                for (Link n : links) {
                    if (n == null) continue;
                    for (Link o : old.links) {
                        if (o == null) continue;
                        if (n.agentId != null && n.agentId.equals(o.agentId)
                                && n.linkKey != null && n.linkKey.equals(o.linkKey)) {
                            String last = maxU64(n.lastCallCounter, o.lastCallCounter);
                            String result = maxU64(n.resultCounter, o.resultCounter);
                            if (!last.equals(n.lastCallCounter) || !result.equals(n.resultCounter)) {
                                n.lastCallCounter = last;
                                n.resultCounter = result;
                                changed = true;
                            }
                        }
                    }
                }
            }
            if (changed) save();
            Files.deleteIfExists(prev);
            return changed;
        } catch (IOException | RuntimeException e) {
            // Preserve the only durable copy if parsing or persistence failed.
            throw e;
        }
    }

    static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    static String maxU64(String a, String b) {
        String x = a == null || a.isBlank() ? "0" : a.trim();
        String y = b == null || b.isBlank() ? "0" : b.trim();
        try {
            return Long.compareUnsigned(Long.parseUnsignedLong(x), Long.parseUnsignedLong(y)) >= 0 ? x : y;
        } catch (NumberFormatException e) {
            return x;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Link> parseLinks(Object raw) {
        List<Link> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) item;
            Link link = new Link();
            link.agentId = str(m, "agentId");
            link.linkKey = str(m, "linkKey");
            String last = counter(m, "lastCallCounter");
            String result = counter(m, "resultCounter");
            link.lastCallCounter = last.isEmpty() ? "0" : last;
            link.resultCounter = result.isEmpty() ? "0" : result;
            if (!link.agentId.isEmpty() && !link.linkKey.isEmpty()) out.add(link);
        }
        return out;
    }

    private static String counter(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (v == null) return "";
        return String.valueOf(v).trim();
    }

    private static String str(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
