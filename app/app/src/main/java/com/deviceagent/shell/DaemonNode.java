package com.deviceagent.shell;

import com.deviceagent.app.BuildInfo;
import com.deviceagent.crypto.LinkStore;
import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.daemon.DaemonLog;
import com.deviceagent.rpc.ControlServer;
import com.deviceagent.rpc.DeviceNode;
import com.deviceagent.tools.AndroidTools;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relay-poll supervisor hosted in the uid-2000 daemon.
 * {@link ControlServer} is started by {@link com.deviceagent.daemon.Main};
 * this class owns the outbound {@link DeviceNode} only.
 */
public final class DaemonNode {
    private final AndroidTools tools;
    private final DaemonConfig config;
    private final LinkStore links;
    private final Object lock = new Object();
    private final Deque<String> log = new ArrayDeque<>();

    private volatile DeviceNode node;
    private Thread pollThread;
    private volatile String lastError;
    private long configuredAt;
    private volatile boolean allowlisted;

    public DaemonNode(AndroidTools tools, DaemonConfig config, LinkStore links) {
        this.tools = tools;
        this.config = config == null ? new DaemonConfig() : config;
        this.links = links;
    }

    public Map<String, Object> configure(String relayUrl, String deviceToken, String deviceName) {
        return configure(relayUrl, deviceToken, deviceName, null);
    }

    public Map<String, Object> configure(String relayUrl, String deviceToken, String deviceName, String deviceId) {
        synchronized (lock) {
            configuredAt = System.currentTimeMillis();
            boolean restart = false;
            if (relayUrl != null) {
                config.relayUrl = relayUrl.trim();
                restart = true;
            }
            if (deviceToken != null) {
                config.deviceToken = deviceToken.trim();
                restart = true;
            }
            if (deviceName != null) {
                config.deviceName = deviceName.trim();
                restart = true;
            }
            if (deviceId != null) {
                config.deviceId = deviceId.trim();
                restart = true;
            }
            try {
                config.save();
            } catch (Exception e) {
                throw new IllegalStateException("Could not save device configuration", e);
            }
            if (!allowlisted) hardenRuntime();
            keepAdbRunning();
            if (restart || pollThread == null || !pollThread.isAlive()) restartPollLocked();
            return status();
        }
    }

    /** Immediately drops any stale long-poll sockets and reconnects to the relay. */
    public Map<String, Object> reconnect() {
        synchronized (lock) {
            if (config.hasRelay()) {
                record("reconnect requested");
                restartPollLocked();
            }
            return status();
        }
    }

    public Map<String, Object> status() {
        synchronized (lock) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("inbound", true);
            out.put("inboundPort", config.port > 0 ? config.port : ControlServer.PORT);
            out.put("relayUrl", emptyToNull(config.relayUrl));
            out.put("polling", pollThread != null && pollThread.isAlive());
            out.put("registeredName", registeredName());
            out.put("connected", relayConnected());
            out.put("lastPollAt", lastPollAt() == 0L ? null : lastPollAt());
            out.put("lastError", lastError());
            out.put("configuredAt", configuredAt);
            out.put("uid", android.os.Process.myUid());
            out.put("pid", android.os.Process.myPid());
            List<String> lines = new ArrayList<>(log);
            out.put("log", lines);
            return out;
        }
    }

    public void pushEvent(Map<String, Object> event) {
        DeviceNode n = node;
        if (n != null) n.pushEvent(event);
    }

    public String lastError() {
        DeviceNode n = node;
        String nodeErr = n == null ? null : n.lastError();
        return nodeErr != null && !nodeErr.isBlank() ? nodeErr : lastError;
    }

    public String relayUrl() {
        String u = config.relayUrl;
        return u == null || u.isBlank() ? null : u;
    }

    public boolean relayConnected() {
        DeviceNode n = node;
        return n != null && n.connected();
    }

    public boolean polling() {
        synchronized (lock) { return pollThread != null && pollThread.isAlive(); }
    }

    public long lastPollAt() {
        DeviceNode n = node;
        return n == null ? 0L : n.lastPollAt();
    }

    public String registeredName() {
        DeviceNode n = node;
        if (n != null && n.registeredName() != null) return n.registeredName();
        String name = config.deviceName;
        return name == null || name.isBlank() ? null : name;
    }

    public void shutdown() {
        synchronized (lock) {
            stopPollLocked();
        }
        if (links != null) links.flush();
    }

    private void restartPollLocked() {
        Thread previous = pollThread;
        stopPollLocked();
        if (!config.hasRelay()) {
            record("no relay URL/token; inbound only");
            DaemonLog.print("no relay URL/token; inbound :" + ControlServer.PORT + " only");
            return;
        }
        final String url = config.relayUrl;
        final String token = config.deviceToken;
        final String local = config.localToken;
        final String version = config.agentVersion == null || config.agentVersion.isBlank()
                ? BuildInfo.VERSION : config.agentVersion;
        DeviceNode current = new DeviceNode(url, tools, token, local, version, links, config.deviceId);
        current.setIdleHook(() -> { hardenRuntime(); keepAdbRunning(); });
        node = current;
        pollThread = new Thread(() -> {
            try { if (previous != null) previous.join(3_000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            runPoll(current);
        }, "backstage-poll");
        pollThread.setDaemon(true);
        pollThread.start();
        record("poll started " + url);
    }

    private void stopPollLocked() {
        if (node != null) {
            node.stop();
            node = null;
        }
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private void runPoll(DeviceNode current) {
        long backoffMs = 1000L;
        while (node == current && !Thread.currentThread().isInterrupted()) {
            try {
                current.runForever();
                if (node == current) record("poll ended; restarting");
            } catch (Exception e) {
                if (node != current || Thread.currentThread().isInterrupted()) return;
                lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                record("poll failed: " + lastError);
                DaemonLog.print("poll failed", e);
            }
            if (node != current) return;
            try { Thread.sleep(backoffMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            backoffMs = Math.min(backoffMs * 2L, 30_000L);
        }
    }

    /**
     * Best-effort uid-2000 durability hardening once per daemon start:
     * Doze allowlist, background execution, standby bucket, disable 7-day ADB
     * auth expiry, and disable Android 12-16 Phantom Process Killer monitoring.
     */
    private void hardenRuntime() {
        if (allowlisted) return;
        allowlisted = true;
        String pkg = config.packageName;
        if (pkg == null || pkg.isBlank()) pkg = BuildInfo.DEFAULT_PACKAGE;
        String[] cmds = new String[] {
                "cmd deviceidle whitelist +" + pkg,
                "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                "am set-standby-bucket " + pkg + " active",
                "settings put global adb_allowed_connection_time 0",
                "settings put global settings_enable_monitor_phantom_procs false"
        };
        StringBuilder note = new StringBuilder("runtime harden:");
        for (String cmd : cmds) {
            android.os.Bundle r = LocalShell.run(cmd, null, 8_000L);
            note.append(' ').append(cmd.split(" ")[0]).append('=').append(r.getInt("exit", -1));
        }
        record(note.toString());
        DaemonLog.print(note.toString());
    }

    private volatile boolean settingsGranted;
    private static final java.nio.file.Path USB_OWNED_MARKER =
            java.nio.file.Paths.get(com.deviceagent.daemon.Starter.DIR, "usb-enabled-by-backstage");

    /**
     * Grants WRITE_SECURE_SETTINGS to the Backstage app so it can relaunch after reboot
     * on owner-approved Wi-Fi networks. Only turns on USB debugging when the owner
     * explicitly enabled {@code keepUsbDebugging}, and records a marker so Backstage
     * turns it back off on pause/stop without touching pre-existing developer settings.
     */
    private void keepAdbRunning() {
        String pkg = config.packageName;
        if (pkg == null || pkg.isBlank()) pkg = BuildInfo.DEFAULT_PACKAGE;
        if (!settingsGranted) {
            android.os.Bundle grant = LocalShell.run("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS", null, 8_000L);
            settingsGranted = grant.getInt("exit", -1) == 0;
            String note = "settings grant: " + (settingsGranted ? "ok" : "exit " + grant.getInt("exit", -1));
            record(note);
            DaemonLog.print(note);
        }
        if (!config.keepUsbDebugging) {
            restoreUsbDebuggingIfOwned();
            return;
        }
        android.os.Bundle usb = LocalShell.run("settings get global adb_enabled", null, 4_000L);
        byte[] out = usb.getByteArray("stdout");
        if (out != null && "1".equals(new String(out, java.nio.charset.StandardCharsets.UTF_8).trim())) return;
        try {
            java.nio.file.Path parent = USB_OWNED_MARKER.getParent();
            if (parent != null) java.nio.file.Files.createDirectories(parent);
            java.nio.file.Files.write(USB_OWNED_MARKER, "1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        android.os.Bundle on = LocalShell.run("settings put global adb_enabled 1", null, 4_000L);
        String note = "USB debugging was off; turned on per keepUsbDebugging setting: exit " + on.getInt("exit", -1);
        record(note);
        // Turning USB debugging on restarts adbd, which ends this daemon; the app relaunches it.
        com.deviceagent.daemon.Lifeline.event(note);
    }

    /** Restores adb_enabled=0 on pause/stop or opt-out if Backstage was the one that enabled it. */
    public void restoreUsbDebuggingIfOwned() {
        try {
            if (!java.nio.file.Files.isRegularFile(USB_OWNED_MARKER)) return;
            java.nio.file.Files.deleteIfExists(USB_OWNED_MARKER);
        } catch (Exception e) {
            return;
        }
        android.os.Bundle off = LocalShell.run("settings put global adb_enabled 0", null, 4_000L);
        String note = "restored USB debugging off (enabled by Backstage): exit " + off.getInt("exit", -1);
        record(note);
        DaemonLog.print(note);
    }

    private void record(String line) {
        synchronized (lock) {
            log.addLast(Instant.now() + " " + line);
            while (log.size() > 40) log.removeFirst();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
