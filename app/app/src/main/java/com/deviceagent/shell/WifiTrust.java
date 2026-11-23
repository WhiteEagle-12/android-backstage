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
 * Keeps Wireless debugging on, without Android's "Allow wireless debugging on this
 * network?" dialog, on every access point of the owner's networks.
 *
 * Android turns Wireless debugging off whenever the phone moves to another access
 * point and asks again for each one it has not trusted. While this daemon runs it
 * holds MANAGE_DEBUGGING, so on each such change it approves the new access point
 * (and nearby ones of the same networks) the way the dialog's "Always allow" button
 * would. The app relaunches the daemon over Wireless debugging; it reads the trusted
 * list published here so it never turns Wireless debugging on where Android would ask.
 */
public final class WifiTrust {
    private static final long TICK_MS = 3_000L;
    private static final long SAVED_REFRESH_MS = 10 * 60_000L;
    private static final long PRIME_DELAY_MS = 60_000L;

    private final String packageName;
    private final Path stateFile = Paths.get(Starter.DIR, "wifi-trust.json");
    private final Set<String> learned = new LinkedHashSet<>();
    private Map<String, Boolean> saved = new LinkedHashMap<>();
    private Set<String> trusted = new LinkedHashSet<>();
    private long savedAt;
    private long trustedAt;
    private long startedAt;
    private boolean primed;
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
        this.packageName = packageName == null || packageName.isBlank() ? com.deviceagent.app.BuildInfo.DEFAULT_PACKAGE : packageName;
    }

    public synchronized void start() {
        if (thread != null) return;
        startedAt = System.currentTimeMillis();
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
            // On and trusted: the owner approved this network at least once.
            if (trusted.contains(wifi.bssid) && wifi.ssid != null && learned.add(wifi.ssid)) {
                saveLearned();
                Lifeline.event("learned trusted network \"" + wifi.ssid + "\"");
            }
            if (!primed && eligible && now - startedAt > PRIME_DELAY_MS) prime(wifi);
            return;
        }
        if (!eligible) {
            if (!wifi.bssid.equals(skippedBssid)) {
                skippedBssid = wifi.bssid;
                Lifeline.event("not approving Wireless debugging on \"" + wifi.ssid + "\": not a saved password-protected network");
                publish();
            }
            return;
        }
        if (wifi.bssid.equals(attemptBssid) && now - attemptAt < (failures >= 3 ? 10 * 60_000L : 20_000L)) return;
        approve(wifi, "Wireless debugging was off");
    }

    /**
     * Once per daemon start: approve nearby access points of the owner's networks in
     * advance. Android only accepts approvals while Wireless debugging is off, so this
     * briefly turns it off; USB debugging keeps adbd (and this daemon) running.
     */
    private void prime(SystemBinders.Wifi wifi) {
        primed = true;
        List<String> pending = WifiTrustRules.neighborsToTrust(scan(), saved, learned, trusted, wifi.bssid, 40);
        if (pending.isEmpty()) {
            Lifeline.event("advance approval: every nearby access point of the owner's networks is already trusted");
            return;
        }
        if (!"1".equals(text(LocalShell.run("settings get global adb_enabled", null, 4_000L)))) {
            Lifeline.event("skipping advance approval: USB debugging is off, so turning Wireless debugging off could stop adbd");
            return;
        }
        LocalShell.run("settings put global adb_wifi_enabled 0", null, 4_000L);
        for (int i = 0; i < 20 && SystemBinders.adbWirelessPort() > 0; i++) sleepQuietly(250L);
        approve(SystemBinders.wifi(), "advance approval of " + pending.size() + " nearby access points");
    }

    private void approve(SystemBinders.Wifi wifi, String reason) {
        if (!wifi.connected()) return;
        attemptBssid = wifi.bssid;
        attemptAt = System.currentTimeMillis();
        List<String> neighbors = WifiTrustRules.neighborsToTrust(scan(), saved, learned, trusted, wifi.bssid, 40);
        int added = 0;
        for (String bssid : neighbors) {
            if (SystemBinders.allowWirelessDebugging(bssid)) {
                trusted.add(bssid);
                added++;
            }
        }
        // Android appends to its trust list on every approval, even for a known access point.
        // Where it already trusts this one, switching the setting on is silent and adds nothing.
        boolean known = trusted.contains(wifi.bssid);
        boolean asked = known
                ? LocalShell.run("settings put global adb_wifi_enabled 1", null, 4_000L).getInt("exit", -1) == 0
                : SystemBinders.allowWirelessDebugging(wifi.bssid);
        int nowPort = -1;
        for (int i = 0; i < 20 && (nowPort = SystemBinders.adbWirelessPort()) <= 0; i++) sleepQuietly(250L);
        if (asked) trusted.add(wifi.bssid);
        boolean on = nowPort > 0;
        failures = on ? 0 : failures + 1;
        approvedCount += added + (asked && !known ? 1 : 0);
        lastAction = (on ? (known ? "re-enabled on trusted " : "approved ") : "could not approve ") + wifi + " (" + reason + "; " + added + " nearby access points approved in advance)";
        Lifeline.event(lastAction + (on ? ", port " + nowPort : ", attempt " + failures));
        port = nowPort;
        trustedAt = System.currentTimeMillis() - SAVED_REFRESH_MS + 3_000L;
        publish();
    }

    private List<WifiTrustRules.Scan> scan() {
        return WifiTrustRules.scanResults(text(LocalShell.run("cmd wifi list-scan-results", null, 6_000L)));
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
            for (Map.Entry<String, Boolean> row : saved.entrySet()) if (Boolean.TRUE.equals(row.getValue())) eligible.add(row.getKey());
            for (String ssid : learned) if (!eligible.contains(ssid)) eligible.add(ssid);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("updatedAt", System.currentTimeMillis());
            out.put("trustedBssids", new ArrayList<>(trusted));
            out.put("eligibleSsids", eligible);
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
