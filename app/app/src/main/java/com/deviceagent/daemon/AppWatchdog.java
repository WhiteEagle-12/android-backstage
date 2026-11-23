package com.deviceagent.daemon;

import com.deviceagent.shell.LocalShell;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The app relaunches this daemon; this daemon restarts the app. If a power manager
 * or a crash removes the app's process, the daemon starts the app's supervision
 * service again so the next daemon failure can still be recovered. A running daemon
 * means access is enabled: pausing in the app shuts the daemon down first.
 */
public final class AppWatchdog {
    private static final long CHECK_MS = 30_000L;
    private static final long MIN_RESTART_GAP_MS = 3 * 60_000L;

    private final String packageName;
    private Thread thread;

    public AppWatchdog(String packageName) {
        this.packageName = packageName;
    }

    public synchronized void start() {
        if (thread != null || packageName == null || !packageName.matches("[A-Za-z0-9._]+")) return;
        thread = new Thread(this::run, "backstage-app-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        int missing = 0;
        long lastRestart = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (running(packageName)) {
                missing = 0;
                continue;
            }
            missing++;
            long now = System.currentTimeMillis();
            if (missing < 2 || now - lastRestart < MIN_RESTART_GAP_MS) continue;
            lastRestart = now;
            android.os.Bundle result = LocalShell.run("am start-foreground-service -n " + packageName
                    + "/com.deviceagent.app.AgentService", null, 10_000L);
            byte[] out = result.getByteArray("stdout");
            Lifeline.event("app process was gone; restarted its supervision service: exit " + result.getInt("exit", -1)
                    + " " + (out == null ? "" : new String(out, StandardCharsets.UTF_8).trim().replace('\n', ' ')));
        }
    }

    static boolean running(String packageName) {
        try (DirectoryStream<Path> procs = Files.newDirectoryStream(Paths.get("/proc"), entry -> entry.getFileName().toString().matches("\\d+"))) {
            for (Path proc : procs) {
                try {
                    byte[] raw = Files.readAllBytes(proc.resolve("cmdline"));
                    int end = 0;
                    while (end < raw.length && raw[end] != 0) end++;
                    if (packageName.equals(new String(raw, 0, end, StandardCharsets.UTF_8))) return true;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }
}
