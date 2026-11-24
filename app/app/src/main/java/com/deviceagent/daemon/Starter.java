package com.deviceagent.daemon;

import com.deviceagent.app.BuildInfo;
import com.deviceagent.protocol.ShellCommand;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared shell snippets for launching the uid-2000 daemon. Used by the app
 * supervisor (via wireless ADB) and by {@code android.maintenance}.
 */
public final class Starter {
    public static final String DIR = "/data/local/tmp/backstage";
    public static final String CONFIG_PATH = DIR + "/daemon.json";
    public static final String LOG_PATH = DIR + "/daemon.log";
    public static final String PID_PATH = DIR + "/daemon.pid";
    public static final String WATCHDOG_PID_PATH = DIR + "/watchdog.pid";
    public static final String STOP_MARKER_PATH = DIR + "/daemon.stopped";
    public static final String UPDATE_APK = DIR + "/update.apk";
    public static final String MAIN_CLASS = "com.deviceagent.daemon.Main";

    private Starter() {}

    /**
     * Exact launch line: detached {@code nohup setsid} uid-2000 crash watchdog around
     * {@code app_process}. Preserves log append so prior daemon deaths remain recorded.
     * If {@code Main} exits non-zero without a user stop marker, restarts automatically
     * as uid 2000 even when Wireless debugging is off or the phone is on cellular.
     */
    public static String command(String apkPath, String configPath, String logPath) {
        String apk = apkPath == null || apkPath.isBlank() ? "" : apkPath;
        String cfg = configPath == null || configPath.isBlank() ? CONFIG_PATH : configPath;
        String log = logPath == null || logPath.isBlank() ? LOG_PATH : logPath;
        String wdPid = watchdogPidPathNear(cfg);
        String stopMarker = stopMarkerPathNear(cfg);
        String loop = "echo $$ > " + ShellCommand.quote(wdPid) + "; "
                + "rm -f " + ShellCommand.quote(stopMarker) + "; "
                + "BACKOFF=2; "
                + "while true; do "
                + "START=$(date +%s 2>/dev/null || echo 0); "
                + "app_process /system/bin --nice-name=backstage_daemon "
                + MAIN_CLASS + " --config " + ShellCommand.quote(cfg) + "; "
                + "RC=$?; "
                + "if [ \"$RC\" -eq 0 ] || [ -f " + ShellCommand.quote(stopMarker) + " ]; then "
                + "if [ \"$(cat " + ShellCommand.quote(wdPid) + " 2>/dev/null || true)\" = \"$$\" ]; then rm -f " + ShellCommand.quote(wdPid) + "; fi; "
                + "exit 0; fi; "
                + "NOW=$(date +%s 2>/dev/null || echo 0); "
                + "if [ $((NOW - START)) -ge 30 ]; then BACKOFF=2; fi; "
                + "echo \"[backstage-watchdog] daemon exited ($RC); restarting in ${BACKOFF}s\"; "
                + "sleep \"$BACKOFF\"; "
                + "BACKOFF=$((BACKOFF * 2)); "
                + "if [ \"$BACKOFF\" -gt 30 ]; then BACKOFF=30; fi; "
                + "done";
        return "CLASSPATH=" + ShellCommand.quote(apk)
                + " nohup setsid sh -c " + ShellCommand.quote(loop)
                + " >>" + ShellCommand.quote(log) + " 2>&1 </dev/null &";
    }

    /**
     * Stop the previous watchdog + daemon writer, preserve recovery state,
     * atomically replace the bootstrap config, launch, and wait for the daemon's
     * readiness pidfile.
     */
    public static String writeConfigAndStart(String apkPath, String configJson, String configPath, String logPath) {
        String cfg = configPath == null || configPath.isBlank() ? CONFIG_PATH : configPath;
        String log = logPath == null || logPath.isBlank() ? LOG_PATH : logPath;
        String pid = pidPathNear(cfg);
        String wdPid = watchdogPidPathNear(cfg);
        String stopMarker = stopMarkerPathNear(cfg);
        String json = configJson == null ? "{}" : configJson;
        if (!json.endsWith("\n")) json = json + "\n";

        StringBuilder sh = new StringBuilder();
        sh.append("set -eu\numask 077\n");
        sh.append("mkdir -p ").append(ShellCommand.quote(DIR)).append('\n');
        // Mark stop first so any old watchdog exits instead of respawning while we swap config.
        sh.append(": > ").append(ShellCommand.quote(stopMarker)).append('\n');
        sh.append("if [ -s ").append(ShellCommand.quote(wdPid)).append(" ]; then kill \"$(cat ")
                .append(ShellCommand.quote(wdPid)).append(")\" 2>/dev/null || true; fi\n");
        // Stop by pidfile AND by process name (both 16-char cmdline and 15-char /proc/comm).
        sh.append("if [ -s ").append(ShellCommand.quote(pid)).append(" ]; then kill \"$(cat ")
                .append(ShellCommand.quote(pid)).append(")\" 2>/dev/null || true; fi\n");
        sh.append("for P in $(pidof backstage_daemon 2>/dev/null || true) $(pidof backstage_daemo 2>/dev/null || true); do kill \"$P\" 2>/dev/null || true; done\n");
        sh.append("sleep 0.4\n");
        sh.append("if [ -s ").append(ShellCommand.quote(wdPid)).append(" ]; then kill -9 \"$(cat ")
                .append(ShellCommand.quote(wdPid)).append(")\" 2>/dev/null || true; fi\n");
        sh.append("if [ -s ").append(ShellCommand.quote(pid)).append(" ]; then kill -9 \"$(cat ")
                .append(ShellCommand.quote(pid)).append(")\" 2>/dev/null || true; fi\n");
        sh.append("for P in $(pidof backstage_daemon 2>/dev/null || true) $(pidof backstage_daemo 2>/dev/null || true); do kill -9 \"$P\" 2>/dev/null || true; done\n");
        sh.append("rm -f ").append(ShellCommand.quote(pid)).append(' ').append(ShellCommand.quote(wdPid))
                .append(' ').append(ShellCommand.quote(stopMarker)).append('\n');
        // An unsuccessful prior launch may already have saved the only good state.
        // Never overwrite that backup with the app's stale counter snapshot.
        sh.append("if [ -f ").append(ShellCommand.quote(cfg)).append(" ] && [ ! -e ")
                .append(ShellCommand.quote(cfg + ".prev")).append(" ]; then\n");
        sh.append("  cp ").append(ShellCommand.quote(cfg)).append(' ').append(ShellCommand.quote(cfg + ".prev.tmp")).append('\n');
        sh.append("  mv ").append(ShellCommand.quote(cfg + ".prev.tmp")).append(' ').append(ShellCommand.quote(cfg + ".prev")).append('\n');
        sh.append("fi\n");
        sh.append("cat > ").append(ShellCommand.quote(cfg + ".incoming")).append(" <<'EOF'\n");
        sh.append(json);
        sh.append("EOF\n");
        sh.append("chmod 600 ").append(ShellCommand.quote(cfg + ".incoming")).append('\n');
        sh.append("mv ").append(ShellCommand.quote(cfg + ".incoming")).append(' ').append(ShellCommand.quote(cfg)).append('\n');
        // Keep earlier daemons' logs: they hold the only record of why a daemon stopped.
        sh.append(rotateLog(log));
        sh.append(command(apkPath, cfg, log)).append('\n');
        // Keep the ADB shell open until app_process has detached and bound its socket.
        // An immediate shell exit can kill the child before nohup/setsid executes.
        sh.append("TRIES=0\nwhile [ ! -s ").append(ShellCommand.quote(pid)).append(" ] && [ \"$TRIES\" -lt 40 ]; do\n");
        sh.append("  sleep 0.25\n  TRIES=$((TRIES + 1))\ndone\n");
        sh.append("if [ -s ").append(ShellCommand.quote(pid)).append(" ]; then echo started; else echo 'startup not confirmed; check daemon log'; exit 1; fi\n");
        return sh.toString();
    }

    /** Rotate {@code log} past 1 MiB, keeping three older files, and mark the new launch. */
    static String rotateLog(String log) {
        String q = ShellCommand.quote(log);
        String q1 = ShellCommand.quote(log + ".1"), q2 = ShellCommand.quote(log + ".2"), q3 = ShellCommand.quote(log + ".3");
        return "if [ -f " + q + " ] && [ $(wc -c < " + q + ") -gt 1048576 ]; then\n"
                + "  if [ -f " + q2 + " ]; then mv -f " + q2 + " " + q3 + "; fi\n"
                + "  if [ -f " + q1 + " ]; then mv -f " + q1 + " " + q2 + "; fi\n"
                + "  mv -f " + q + " " + q1 + "\n"
                + "fi\n"
                + "echo \"=== launch $(date -u +%Y-%m-%dT%H:%M:%SZ) ===\" >> " + q + "\n";
    }

    public static String writeConfigAndStart(String apkPath, String configJson) {
        return writeConfigAndStart(apkPath, configJson, CONFIG_PATH, LOG_PATH);
    }

    /** Contents of {@code start.sh} for the computer-side fallback. */
    public static String startScript(String apkPath, String configJson) {
        return "#!/system/bin/sh\n"
                + "# Android Backstage — from a computer:\n"
                + "#   adb shell sh /sdcard/Android/data/" + BuildInfo.DEFAULT_PACKAGE + "/files/start.sh\n"
                + writeConfigAndStart(apkPath, configJson, CONFIG_PATH, LOG_PATH);
    }

    public static String configJson(
            String relayUrl,
            String deviceToken,
            String localToken,
            String deviceName,
            String agentVersion,
            String apkPath,
            String packageName) {
        return configJson(relayUrl, deviceToken, localToken, deviceName, agentVersion, apkPath, packageName, 18422, null, null);
    }

    public static String configJson(
            String relayUrl,
            String deviceToken,
            String localToken,
            String deviceName,
            String agentVersion,
            String apkPath,
            String packageName,
            int port) {
        return configJson(relayUrl, deviceToken, localToken, deviceName, agentVersion, apkPath, packageName, port, null, null);
    }

    public static String configJson(
            String relayUrl,
            String deviceToken,
            String localToken,
            String deviceName,
            String agentVersion,
            String apkPath,
            String packageName,
            int port,
            List<DaemonConfig.Link> links,
            String deviceId) {
        return configJson(relayUrl, deviceToken, localToken, deviceName, agentVersion, apkPath, packageName, port, links, deviceId, false);
    }

    public static String configJson(
            String relayUrl,
            String deviceToken,
            String localToken,
            String deviceName,
            String agentVersion,
            String apkPath,
            String packageName,
            int port,
            List<DaemonConfig.Link> links,
            String deviceId,
            boolean keepUsbDebugging) {
        return configJson(relayUrl, deviceToken, localToken, deviceName, agentVersion, apkPath, packageName, port, links, deviceId, keepUsbDebugging, false);
    }

    public static String configJson(
            String relayUrl,
            String deviceToken,
            String localToken,
            String deviceName,
            String agentVersion,
            String apkPath,
            String packageName,
            int port,
            List<DaemonConfig.Link> links,
            String deviceId,
            boolean keepUsbDebugging,
            boolean allowShell) {
        DaemonConfig c = new DaemonConfig();
        c.relayUrl = empty(relayUrl);
        c.deviceToken = empty(deviceToken);
        c.localToken = empty(localToken);
        c.deviceName = empty(deviceName);
        c.deviceId = empty(deviceId);
        c.agentVersion = empty(agentVersion).isEmpty() ? BuildInfo.VERSION : agentVersion;
        c.apkPath = empty(apkPath);
        c.packageName = empty(packageName).isEmpty() ? BuildInfo.DEFAULT_PACKAGE : packageName;
        c.port = port > 0 ? port : 18422;
        c.keepUsbDebugging = keepUsbDebugging;
        c.allowShell = allowShell;
        if (links != null) c.links = new ArrayList<>(links);
        return c.toJson();
    }

    static String pidPathNear(String configPath) {
        return siblingPath(configPath, "daemon.pid", PID_PATH);
    }

    public static String watchdogPidPathNear(String configPath) {
        return siblingPath(configPath, "watchdog.pid", WATCHDOG_PID_PATH);
    }

    public static String stopMarkerPathNear(String configPath) {
        return siblingPath(configPath, "daemon.stopped", STOP_MARKER_PATH);
    }

    private static String siblingPath(String configPath, String fileName, String fallback) {
        if (configPath == null || configPath.isBlank()) return fallback;
        int slash = configPath.lastIndexOf('/');
        if (slash <= 0) return fallback;
        return configPath.substring(0, slash) + "/" + fileName;
    }

    private static String empty(String s) {
        return s == null ? "" : s;
    }
}
