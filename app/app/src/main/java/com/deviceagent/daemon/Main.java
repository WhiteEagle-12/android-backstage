package com.deviceagent.daemon;

import android.content.Context;
import android.os.Looper;
import com.deviceagent.app.BuildInfo;
import com.deviceagent.crypto.LinkStore;
import com.deviceagent.rpc.ControlServer;
import com.deviceagent.shell.AccessibilityHost;
import com.deviceagent.shell.DaemonNode;
import com.deviceagent.shell.LocalExecutor;
import com.deviceagent.shell.VirtualDisplayHost;
import com.deviceagent.tools.AndroidTools;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * uid-2000 entry point, started by {@code app_process} (see {@link Starter#command}).
 */
public final class Main {
    private static VirtualDisplayHost virtualDisplay;
    private static ControlServer controlServer;
    private static DaemonNode daemon;
    private static com.deviceagent.shell.WifiTrust wifiTrust;
    private static volatile String activeConfigPath = Starter.CONFIG_PATH;
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public static void main(String[] args) {
        final Thread mainThread = Thread.currentThread();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            DaemonLog.print("uncaught in " + (t == null ? "?" : t.getName()), e);
            if (e instanceof OutOfMemoryError || t == mainThread) {
                System.exit(1);
            }
        });

        String configPath = Starter.CONFIG_PATH;
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            } else if (args[i] != null && args[i].startsWith("--config=")) {
                configPath = args[i].substring("--config=".length());
            }
        }
        activeConfigPath = configPath;
        DaemonLog.print("Main starting pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid()
                + " config=" + configPath
                + " version=" + BuildInfo.VERSION);

        DaemonConfig config;
        try {
            config = DaemonConfig.load(configPath);
        } catch (Exception e) {
            DaemonLog.print("failed to read " + configPath, e);
            System.exit(2);
            return;
        }
        try {
            if (config.mergePrevCounters()) {
                DaemonLog.print("merged link counters from previous daemon.json");
            }
        } catch (Exception e) {
            DaemonLog.print("could not merge previous link counters; refusing startup", e);
            System.exit(2);
            return;
        }
        if (config.localToken == null || config.localToken.isBlank()) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            config.localToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            DaemonLog.print("daemon.json had no localToken; generated one (inbound would otherwise be open)");
            try {
                config.save();
            } catch (Exception e) {
                DaemonLog.print("could not persist generated localToken; refusing startup", e);
                System.exit(2);
                return;
            }
        }
        config.agentVersion = BuildInfo.VERSION;
        if (config.packageName == null || config.packageName.isBlank()) {
            config.packageName = BuildInfo.DEFAULT_PACKAGE;
        }

        Context context = SystemContext.create(config.packageName);
        DaemonLog.print("bootstrap context = " + (context == null ? "null" : context.getClass().getName()));

        virtualDisplay = new VirtualDisplayHost(context);
        AccessibilityHost accessibility = new AccessibilityHost();
        com.deviceagent.shell.InteractionHost interaction = new com.deviceagent.shell.InteractionHost(context);
        LocalExecutor executor = new LocalExecutor(virtualDisplay, accessibility, interaction);
        AndroidTools tools = new AndroidTools(executor, config);
        LinkStore links = new LinkStore(config);
        daemon = new DaemonNode(tools, config, links);
        tools.setEventEmitter(daemon::pushEvent);
        com.deviceagent.shell.EventWatch eventWatch = new com.deviceagent.shell.EventWatch(config, interaction, daemon::pushEvent);
        tools.setEventRulesChanged(eventWatch::nudge);
        eventWatch.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!shuttingDown.get()) Lifeline.stopping("terminated by signal (replaced or stopped)");
            release();
        }, "backstage-shutdown"));

        wifiTrust = new com.deviceagent.shell.WifiTrust(config.packageName, config.localToken);
        java.util.function.Supplier<java.util.Map<String, Object>> liveStatus = () -> {
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("network", com.deviceagent.shell.NetworkWatch.signature());
            out.put("relayConnected", daemon != null && daemon.relayConnected());
            out.put("lastPollAt", daemon == null ? 0L : daemon.lastPollAt());
            out.put("wifiTrust", wifiTrust.status());
            out.put("wifi", wifiTrust.status().get("wifi"));
            return out;
        };

        controlServer = new ControlServer(tools, config, links, daemon, virtualDisplay, Main::shutdown).port(config.port).extraHealth(liveStatus);
        // A previous daemon (self-update, supervisor restart) may still hold the port for a moment.
        long bindDeadline = System.currentTimeMillis() + 30_000L;
        boolean bound = controlServer.start();
        while (!bound && System.currentTimeMillis() < bindDeadline) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            bound = controlServer.start();
        }
        if (!bound) {
            DaemonLog.print("ControlServer bind :" + config.port + " failed for 30s (port busy?)");
            System.exit(3);
            return;
        }
        DaemonLog.print("ControlServer listening 0.0.0.0:" + config.port);
        // Only the daemon that owns the port may write the heartbeat and lifecycle record.
        Lifeline.start(liveStatus);
        com.deviceagent.shell.NetworkWatch.start();
        // Only after the bind: a daemon that lost the port must not overwrite the live pid.
        try {
            Files.deleteIfExists(java.nio.file.Paths.get(Starter.stopMarkerPathNear(configPath)));
            Path pidFile = java.nio.file.Paths.get(Starter.pidPathNear(configPath));
            Path parent = pidFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(pidFile, Integer.toString(android.os.Process.myPid()).getBytes(StandardCharsets.UTF_8));
            DaemonLog.print("wrote " + pidFile);
        } catch (Exception e) {
            DaemonLog.print("could not write daemon.pid", e);
        }


        daemon.configure(config.relayUrl, config.deviceToken, config.deviceName, config.deviceId);
        wifiTrust.start();
        new AppWatchdog(config.packageName).start();
        DaemonLog.print("configured relayUrl=" + empty(config.relayUrl)
                + " deviceName=" + empty(config.deviceName)
                + " hasDeviceToken=" + (config.deviceToken != null && !config.deviceToken.isBlank()));

        DaemonLog.print("entering Looper.loop()");
        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }
            Looper.loop();
        } catch (Throwable t) {
            DaemonLog.print("Looper.loop() exited", t);
            release();
            System.exit(1);
        }
    }

    /** POST /shutdown: reply is already on the wire; mark intentional stop, release the display and exit 0. */
    public static void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        DaemonLog.print("shutdown requested");
        Lifeline.stopping("shutdown requested by the app (pause, update or relaunch)");
        try {
            Path marker = java.nio.file.Paths.get(Starter.stopMarkerPathNear(activeConfigPath));
            Path parent = marker.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(marker, "stopped\n".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        try {
            if (daemon != null) daemon.restoreUsbDebuggingIfOwned();
        } catch (Throwable t) {
            DaemonLog.print("restoreUsbDebuggingIfOwned failed", t);
        }
        release();
        System.exit(0);
    }

    private static void release() {
        try {
            if (daemon != null) daemon.shutdown();
        } catch (Throwable t) {
            DaemonLog.print("daemon.shutdown failed", t);
        }
        try {
            if (controlServer != null) controlServer.stop();
        } catch (Throwable t) {
            DaemonLog.print("controlServer.stop failed", t);
        }
        try {
            if (virtualDisplay != null) virtualDisplay.handle("stop", null);
        } catch (Throwable t) {
            DaemonLog.print("virtualDisplay.stop failed", t);
        }
        DaemonLog.print("released");
    }

    private static String empty(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
