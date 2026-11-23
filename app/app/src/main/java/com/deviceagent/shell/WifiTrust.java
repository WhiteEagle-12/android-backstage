package com.deviceagent.shell;

import com.deviceagent.daemon.DaemonLog;
import com.deviceagent.daemon.Lifeline;
import com.deviceagent.daemon.Starter;
import com.deviceagent.tools.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps Wireless debugging on without re-prompting on multi-AP access points of
 * password-protected networks the owner has already approved once.
 *
 * Security boundary:
 * - Only SSIDs explicitly approved by the owner (learned when Wireless debugging is
 *   already active and trusted on that network) are eligible. Arbitrary saved Wi-Fi
 *   networks (hotels, offices, cafes) and open/OWE networks are never auto-approved.
 * - Only the currently connected, WPA-authenticated BSSID is approved; nearby scan
 *   results are never pre-approved by SSID name (prevents evil-twin BSSID pre-trust).
 * - Published wifi-trust.json is authenticated with HMAC-SHA256 using localToken so
 *   apps with external storage access cannot forge trusted BSSIDs.
 */
public final class WifiTrust {
    private static final long TICK_MS = 3_000L;
    private static final long SAVED_REFRESH_MS = 10 * 60_000L;

    private final String packageName;
    private final String localToken;
    private final Path stateFile = Paths.get(Starter.DIR, "wifi-trust.json");
    private final Set<String> learned = new LinkedHashSet<>();
    private Map<String, Boolean> saved = new LinkedHashMap<>();
    private Set<String> trusted = new LinkedHashSet<>();
    private long savedAt;
    private long trustedAt;
    private String attemptBssid;
    private long attemptAt;
    private int failures;
    private String skippedBssid;
    private volatile SystemBinders.Wifi current = SystemBinders.Wifi.NONE;
    private volatile int port = -2;
    private volatile String lastAction;
    private volatile int approvedCount;
    private Thread thread;

    public WifiTrust(String packageName) {
        this(packageName, "");
    }

    public WifiTrust(String packageName, String localToken) {
        this.packageName = packageName == null || packageName.isBlank() ? com.deviceagent.app.BuildInfo.DEFAULT_PACKAGE : packageName;
        this.localToken = localToken == null ? "" : localToken;
    }

    public synchronized void start() {
        if (thread != null) return;
        loadLearned();
        thread = new Thread(this::run, "backstage-wifi-trust");
        thread.setDaemon(true);
        thread.start();
    }

    public Map<String, Object> status() {
        SystemBinders.Wifi wifi = current;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wifi", wifi.connected() ? wifi.toString() : null);
        out.put("wirelessDebuggingPort", port);
        out.put("currentTrusted", wifi.connected() && trusted.contains(wifi.bssid));
        out.put("approvedByBackstage", approvedCount);
        out.put("lastAction", lastAction);
        return out;
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                tick();
            } catch (Throwable t) {
                DaemonLog.print("wifi trust tick failed", t);
            }
            if (!NetworkWatch.sleep(TICK_MS)) return;
        }
    }

    private void tick() {
        SystemBinders.Wifi wifi = SystemBinders.wifi();
        int nowPort = SystemBinders.adbWirelessPort();
        if (!wifi.toString().equals(current.toString())) Lifeline.event("wifi: " + current + " -> " + wifi);
        if (nowPort != port && port != -2) Lifeline.event("wireless debugging " + (nowPort > 0 ? "on, port " + nowPort : nowPort == -1 ? "off" : "unknown"));
        current = wifi;
        port = nowPort;
        long now = System.currentTimeMillis();
        if (now - savedAt > SAVED_REFRESH_MS) refreshSaved();
        if (now - trustedAt > SAVED_REFRESH_MS) refreshTrusted();
        if (!wifi.connected() || nowPort == -2) return;
        boolean eligible = WifiTrustRules.eligible(wifi.ssid, saved, learned);
        if (nowPort > 0) {
            // On and trusted: the owner explicitly approved this secured network at least once.
            if (trusted.contains(wifi.bssid)
                    && wifi.ssid != null
                    && !wifi.ssid.isEmpty()
                    && !Boolean.FALSE.equals(saved.get(wifi.ssid))
                    && learned.add(wifi.ssid)) {
                saveLearned();
                Lifeline.event("learned owner-approved network \"" + wifi.ssid + "\"");
                publish();
            }
            return;
        }
        if (!eligible) {
            if (!wifi.bssid.equals(skippedBssid)) {
                skippedBssid = wifi.bssid;
                Lifeline.event("not approving Wireless debugging on \"" + wifi.ssid + "\": network not owner-approved");
                publish();
            }
            return;
        }
        if (wifi.bssid.equals(attemptBssid) && now - attemptAt < (failures >= 3 ? 10 * 60_000L : 20_000L)) return;
        approve(wifi, "Wireless debugging was off");
    }

    private void approve(SystemBinders.Wifi wifi, String reason) {
        if (!wifi.connected()) return;
        attemptBssid = wifi.bssid;
        attemptAt = System.currentTimeMillis();
        // Approve only the currently connected, authenticated BSSID (never pre-approve
        // unauthenticated neighbor BSSIDs from scan results).
        boolean known = trusted.contains(wifi.bssid);
        boolean asked = known
                ? LocalShell.run("settings put global adb_wifi_enabled 1", null, 4_000L).getInt("exit", -1) == 0
                : SystemBinders.allowWirelessDebugging(wifi.bssid);
        int nowPort = -1;
        for (int i = 0; i < 20 && (nowPort = SystemBinders.adbWirelessPort()) <= 0; i++) sleepQuietly(250L);
        if (asked) trusted.add(wifi.bssid);
        boolean on = nowPort > 0;
        failures = on ? 0 : failures + 1;
        approvedCount += (asked && !known ? 1 : 0);
        lastAction = (on ? (known ? "re-enabled on trusted " : "approved ") : "could not approve ") + wifi + " (" + reason + ")";
        Lifeline.event(lastAction + (on ? ", port " + nowPort : ", attempt " + failures));
        port = nowPort;
        trustedAt = System.currentTimeMillis() - SAVED_REFRESH_MS + 3_000L;
        publish();
    }

    private void refreshSaved() {
        savedAt = System.currentTimeMillis();
        Map<String, Boolean> next = WifiTrustRules.savedNetworks(text(LocalShell.run("cmd wifi list-networks", null, 6_000L)));
        if (!next.isEmpty()) saved = next;
    }

    private void refreshTrusted() {
        trustedAt = System.currentTimeMillis();
        String dump = text(LocalShell.run("dumpsys adb", null, 8_000L));
        if (dump.contains("keystore")) trusted = WifiTrustRules.trustedBssids(dump);
        publish();
    }

    /** The app reads this to decide whether turning Wireless debugging on would ask the owner. */
    private void publish() {
        try {
            Path dir = Paths.get("/storage/emulated/0/Android/data", packageName, "files");
            if (!Files.isDirectory(dir)) return;
            List<String> eligible = new ArrayList<>();
            for (String ssid : learned) {
                if (!Boolean.FALSE.equals(saved.get(ssid)) && !eligible.contains(ssid)) eligible.add(ssid);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("updatedAt", System.currentTimeMillis());
            out.put("trustedBssids", new ArrayList<>(trusted));
            out.put("eligibleSsids", eligible);
            out.put("mac", WifiTrustRules.signTrustedBssids(trusted, localToken));
            Path tmp = dir.resolve("wifi-trust.json.tmp");
            Files.write(tmp, Json.stringify(out).getBytes(StandardCharsets.UTF_8));
            tmp.toFile().setReadable(true, false);
            Files.move(tmp, dir.resolve("wifi-trust.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            DaemonLog.print("could not publish wifi trust: " + e.getMessage());
        }
    }

    private void loadLearned() {
        try {
            if (!Files.exists(stateFile)) return;
            Object list = Json.object(new String(Files.readAllBytes(stateFile), StandardCharsets.UTF_8)).get("learnedSsids");
            if (list instanceof List<?> rows) for (Object row : rows) if (row instanceof String ssid && !ssid.isEmpty()) learned.add(ssid);
        } catch (Exception ignored) {
        }
    }

    private void saveLearned() {
        try {
            Files.write(stateFile, Json.stringify(Json.mapOf("learnedSsids", new ArrayList<>(learned))).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String text(android.os.Bundle result) {
        byte[] out = result.getByteArray("stdout");
        return out == null ? "" : new String(out, StandardCharsets.UTF_8).trim();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
