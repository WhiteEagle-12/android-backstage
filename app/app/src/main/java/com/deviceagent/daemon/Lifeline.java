package com.deviceagent.daemon;

import com.deviceagent.shell.SystemBinders;
import com.deviceagent.tools.Json;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Durable record of why the daemon stops. init kills this process with SIGKILL
 * whenever adbd stops, so nothing runs at death. Instead the daemon writes a
 * heartbeat every few seconds and logs USB and ADB property changes the moment
 * they happen; the next daemon compares the last heartbeat with the present.
 * {@code lifecycle.log} keeps one line per start, stop and cause.
 */
public final class Lifeline {
    public static final String LOG_PATH = Starter.DIR + "/lifecycle.log";
    public static final String HEARTBEAT_PATH = Starter.DIR + "/heartbeat.json";
    private static final long LOG_LIMIT = 512 * 1024;
    /** USB and adbd state, plus screen (1 off, 2 on) and charger (0 unplugged) so a stopped adbd can be traced to what preceded it. */
    private static final String[] WATCHED = {"sys.usb.config", "sys.usb.state", "persist.sys.usb.config", "init.svc.adbd", "sys.usb.ffs.ready",
            "debug.tracing.screen_state", "debug.tracing.plug_type"};

    private static volatile Supplier<Map<String, Object>> extra = Map::of;
    private static Thread thread;

    private Lifeline() {}

    public static synchronized void event(String line) {
        Path path = Paths.get(LOG_PATH);
        try {
            if (Files.exists(path) && Files.size(path) > LOG_LIMIT) {
                Files.move(path, Paths.get(LOG_PATH + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileWriter w = new FileWriter(LOG_PATH, true)) {
                w.write(Instant.now() + " " + line.replace('\n', ' ') + "\n");
            }
        } catch (Exception ignored) {
        }
        DaemonLog.print("lifeline: " + line);
    }

    /** The adbd whose cgroup holds this process; init kills the daemon when that adbd stops. */
    public static long adbdPid() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/self/cgroup"))) {
                int at = line.indexOf("/pid_");
                if (at >= 0) return Long.parseLong(line.substring(at + 5).trim());
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Report how the previous daemon ended, then keep a heartbeat and a property watch running. */
    public static synchronized void start(Supplier<Map<String, Object>> status) {
        if (thread != null) return;
        extra = status == null ? Map::of : status;
        reportPrevious();
        thread = new Thread(Lifeline::run, "backstage-lifeline");
        thread.setDaemon(true);
        thread.start();
    }

    private static void reportPrevious() {
        long adbd = adbdPid();
        StringBuilder line = new StringBuilder("start pid=").append(android.os.Process.myPid())
                .append(" version=").append(com.deviceagent.app.BuildInfo.VERSION)
                .append(" adbd=").append(adbd).append(" adbdStarted=").append(SystemBinders.prop("ro.boottime.adbd"))
                .append(" usb=").append(SystemBinders.prop("sys.usb.config"))
                .append(" bootId=").append(bootId());
        try {
            Path path = Paths.get(HEARTBEAT_PATH);
            if (Files.exists(path)) {
                Map<String, Object> last = Json.object(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                Object at = last.get("at");
                long gap = at instanceof Number n ? (System.currentTimeMillis() - n.longValue()) / 1000 : -1;
                line.append(" | previous pid=").append(last.get("pid"))
                        .append(" lastBeat=").append(at instanceof Number n ? Instant.ofEpochMilli(n.longValue()).toString() : "?")
                        .append(" (").append(gap).append("s ago)")
                        .append(" adbd=").append(last.get("adbd"))
                        .append(" usb=").append(last.get("sys.usb.config"))
                        .append(" wifi=").append(last.get("wifi"))
                        .append(" net=").append(last.get("network"));
                String cause;
                if (!bootId().equals(String.valueOf(last.get("bootId")))) cause = "phone rebooted";
                else if (Boolean.TRUE.equals(last.get("stopping"))) cause = "stopped on request (" + last.get("stopReason") + ")";
                else if (last.get("adbd") instanceof Number n && n.longValue() == adbd) cause = "daemon exited while adbd kept running (crash or kill)";
                else cause = "adbd stopped or restarted, which kills the daemon";
                line.append(" | cause: ").append(cause);
            } else {
                line.append(" | no previous heartbeat");
            }
        } catch (Exception e) {
            line.append(" | previous heartbeat unreadable: ").append(e.getClass().getSimpleName());
        }
        event(line.toString());
    }

    /** Mark a deliberate stop so the next start does not report it as a failure. */
    public static void stopping(String reason) {
        synchronized (Lifeline.class) { if (thread == null) return; }
        try {
            Map<String, Object> beat = heartbeat();
            beat.put("stopping", true);
            beat.put("stopReason", reason);
            write(beat);
        } catch (Exception ignored) {
        }
        event("stopping: " + reason);
    }

    private static void run() {
        Map<String, String> last = new LinkedHashMap<>();
        for (String key : WATCHED) last.put(key, SystemBinders.prop(key));
        long nextBeat = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                for (String key : WATCHED) {
                    String now = SystemBinders.prop(key);
                    if (!now.equals(last.get(key))) {
                        event("property " + key + ": " + last.get(key) + " -> " + now);
                        last.put(key, now);
                    }
                }
                long now = System.currentTimeMillis();
                if (now >= nextBeat) {
                    write(heartbeat());
                    nextBeat = now + 10_000L;
                }
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                DaemonLog.print("lifeline tick failed", t);
                try { Thread.sleep(5_000L); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static Map<String, Object> heartbeat() {
        Map<String, Object> beat = new LinkedHashMap<>();
        beat.put("pid", android.os.Process.myPid());
        beat.put("at", System.currentTimeMillis());
        beat.put("bootId", bootId());
        beat.put("adbd", adbdPid());
        beat.put("version", com.deviceagent.app.BuildInfo.VERSION);
        for (String key : WATCHED) beat.put(key, SystemBinders.prop(key));
        try { beat.putAll(extra.get()); } catch (Throwable ignored) { }
        return beat;
    }

    private static void write(Map<String, Object> beat) throws Exception {
        Path tmp = Paths.get(HEARTBEAT_PATH + ".tmp");
        Files.write(tmp, Json.stringify(beat).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, Paths.get(HEARTBEAT_PATH), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String bootIdCache;

    static String bootId() {
        if (bootIdCache != null) return bootIdCache;
        try {
            List<String> lines = Files.readAllLines(Paths.get("/proc/sys/kernel/random/boot_id"));
            bootIdCache = lines.isEmpty() ? "?" : lines.get(0).trim();
        } catch (Exception e) {
            bootIdCache = "?";
        }
        return bootIdCache;
    }
}
