package com.deviceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import com.deviceagent.adb.AdbConnection;
import com.deviceagent.adb.AdbKey;
import com.deviceagent.adb.AdbMdns;
import com.deviceagent.daemon.Starter;
import com.deviceagent.rpc.ControlServer;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;
import java.io.File;
import java.io.FileWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AgentService extends Service {
    static volatile String lastDiagnosis = "idle";

    private static final String TAG = "Backstage";
    private static final String CHANNEL = "backstage";
    private static final int NOTIFY_ID = 1;
    private static final File LOG_NAME = new File("supervisor.log");
    private static final int ADB_TCP_PORT = 5555;

    private Thread worker;
    private AdbKey key;
    private volatile boolean running = true;
    private static volatile AgentService activeService;
    private volatile Thread locationThread;
    static final String ACTION_RECONNECT = "com.androidbackstage.RECONNECT";
    private final Object wake = new Object();
    private volatile boolean wakeRequested;
    private volatile boolean userReconnect;
    private Thread watcher;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    private final AdbMdns.Listener mdnsListener = new AdbMdns.Listener() {
        @Override
        public void onPairingService(String host, int port) {
            PairingNotifier.showPairing(AgentService.this, port);
        }

        @Override
        public void onConnectService(String host, int port) {
            if (!"ok".equals(lastDiagnosis)) {
                wake("wireless debug port discovered");
            }
        }

        @Override
        public void onLost(String type) {}
    };
    static AgentService locationService(){AgentService service=activeService;return service!=null&&service.running?service:null;}
    private static int serviceTypes(boolean location){return (Build.VERSION.SDK_INT>=34?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE:0)|(location?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:0);}
    synchronized void beginLocation(){
        if(!running||!AgentConfig.load(this).accessEnabled)throw new IllegalStateException("Access is paused");
        locationThread=Thread.currentThread();
        try{
            startForeground(NOTIFY_ID,notification("location"),serviceTypes(true));
            if(!running||Thread.currentThread().isInterrupted())throw new IllegalStateException("Access is paused");
        }catch(RuntimeException e){locationThread=null;throw e;}
    }
    synchronized void endLocation(){
        if(locationThread==Thread.currentThread())locationThread=null;
        if(running)startForeground(NOTIFY_ID,notification(AgentActivity.lastStatus),serviceTypes(false));
    }
    private void cancelLocation(){Thread thread=locationThread;if(thread!=null)thread.interrupt();}

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.androidbackstage.STOP".equals(intent.getAction())) {
            AgentConfig.setAccessEnabled(this, false);
            RecoveryNotifier.clear(this);
            running = false;
            cancelLocation();
            if (worker != null) worker.interrupt();
            new Thread(() -> {
                try {
                    AgentConfig c = AgentConfig.load(this);
                    SimpleHttp.postJsonOnce("http://127.0.0.1:" + c.daemonPort + "/shutdown", "{}", 4000, c.localToken);
                } catch (Exception e) {
                    Log.e(TAG, "Stop failed; open app to retry", e);
                    setLast("Pause not confirmed. Open Backstage to retry.");
                    return;
                }
                stopSelf();
            }, "backstage-stop-notification").start();
            return START_NOT_STICKY;
        }
        if (!AgentConfig.load(this).accessEnabled) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            // The owner tapped Reconnect: Android may now show its own approval for this network.
            userReconnect = true;
            record("owner asked to reconnect");
            wake("reconnect tapped");
        }
        requestNotifications();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, BuildInfo.PRODUCT, NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
        PairingNotifier.ensureChannel(this);
        try {
            startForeground(NOTIFY_ID, notification("starting"), serviceTypes(locationThread != null));
        } catch (RuntimeException e) {
            Log.w(TAG, "startForeground refused", e);
            record("startForeground refused: " + e.getMessage());
            AgentConfig.setBootBlocked(this, true);
            RecoveryNotifier.showOpenApp(this);
            BootReceiver.scheduleRetry(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        AgentConfig.setBootBlocked(this, false);
        BootReceiver.cancelRetry(this);
        activeService = this;
        if (worker != null && worker.isAlive()) {
            AgentRequests.start(this);
            if (!"ok".equals(lastDiagnosis)) {
                AdbDiscovery.refresh(this);
                wake("service nudged");
            }
            return START_STICKY;
        }
        running = true;
        AdbDiscovery.acquire(this, mdnsListener);
        worker = new Thread(this::runLoop, "backstage-supervisor");
        worker.setDaemon(true);
        worker.start();
        startWatcher();
        watchNetwork();
        watchDebugState();
        AgentRequests.start(this);
        return START_STICKY;
    }

    private void runLoop() {
        AgentConfig config = AgentConfig.load(this);
        try {
            key = AdbKey.loadOrCreate(new File(getFilesDir(), "adb"), "backstage@" + Build.MODEL);
        } catch (Exception e) {
            setLast("ADB key failed: " + e.getMessage());
            Log.w(TAG, "AdbKey", e);
        }
        tryStart(config);

        while (running && !Thread.currentThread().isInterrupted()) {
            String reason;
            try {
                reason = waitForWake("ok".equals(lastDiagnosis) ? 30_000L : 10_000L);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) return;
            config = AgentConfig.load(this);
            // Debugging is a bootstrap path, not the relay transport. A healthy
            // shell daemon needs no Wi-Fi and must keep working on cellular.
            if (!healthOk()) {
                setLast("daemon down (" + reason + "); relaunching");
                tryStart(config);
            } else {
                lastDiagnosis = "ok";
                ensurePolling(config);
                RecoveryNotifier.clear(this);
                setLast(healthSummary());
            }
        }
    }

    private void tryStart(AgentConfig config) {
        if (!running || Thread.currentThread().isInterrupted() || !AgentConfig.load(this).accessEnabled) return;
        try {
            if(ConnectionRecovery.foreignService(config.daemonPort)) {
                AgentConfig.savePort(this,ConnectionRecovery.availablePort(config.daemonPort));
                config=AgentConfig.load(this);
                setLast("Moved Backstage to a free local port");
            }
        } catch(Exception e) { setLast("Could not check the local connection: "+e.getClass().getSimpleName());return; }
        if (healthOk()) {
            lastDiagnosis = "ok";
            ensurePolling(config);
            RecoveryNotifier.clear(this);
            setLast(healthSummary());
            return;
        }
        if (key == null) {
            lastDiagnosis = "error";
            setLast("no ADB key");
            return;
        }
        // Shizuku and `adb tcpip` leave adbd listening on plain TCP; that works without Wireless debugging.
        boolean asked = userReconnect;
        userReconnect = false;
        AdbLaunchAttempt attempt = openAdbOnce(ADB_TCP_PORT, 3_000);
        if (attempt.connection == null && wirelessDebuggingOff()) {
            String blocked = asked ? null : WifiApproval.blockedReason(this);
            if (blocked != null) {
                lastDiagnosis = "wireless_off";
                // Never make Android ask on its own: wait for an approved access point,
                // or for the owner to tap Reconnect.
                RecoveryNotifier.showReconnect(this, blocked);
                setLast("Phone access paused until an approved Wi-Fi: " + blocked);
                return;
            }
            if (!turnOnWirelessDebugging(asked)) {
                lastDiagnosis = "wireless_off";
                RecoveryNotifier.showReconnect(this, "Wireless debugging did not turn on.");
                setLast("Wireless debugging did not turn on");
                return;
            }
            AdbDiscovery.refresh(this);
        }
        if (attempt.connection == null) {
            attempt = connectForLaunch(config);
        }
        // If Wireless debugging is already on at an approved AP (or user tapped Reconnect)
        // but adbd's TLS port is stuck/unadvertised/refused, auto-cycle adb_wifi_enabled 1->0->1
        // (rate-limited) before giving up and notifying the user. Never cycle on auth_revoked.
        if (attempt.connection == null
                && !"auth_revoked".equals(attempt.diagnosis)
                && !wirelessDebuggingOff()
                && (asked || WifiApproval.blockedReason(this) == null)
                && cycleWirelessDebugging(asked)) {
            attempt = connectForLaunch(config);
        }
        if (attempt.connection == null) {
            lastDiagnosis = attempt.diagnosis;
            if ("auth_revoked".equals(attempt.diagnosis)) {
                RecoveryNotifier.showOpenApp(this);
            } else {
                RecoveryNotifier.showReconnect(this, "Backstage couldn’t reconnect automatically.");
            }
            setLast(attempt.logMessage);
            return;
        }
        try (AdbConnection conn = attempt.connection) {
            if (attempt.port != ADB_TCP_PORT) AgentConfig.saveAdbState(this, attempt.port, null, true);
            config = AgentConfig.load(this);
            if (!running || Thread.currentThread().isInterrupted() || !config.accessEnabled) return;
            String snippet = Starter.writeConfigAndStart(
                    StartScripts.apkPath(this),
                    StartScripts.configJson(this, config),
                    Starter.CONFIG_PATH,
                    Starter.LOG_PATH);
            // exec: (no PTY) so closing the stream cannot SIGHUP the setsid'd daemon.
            // A PTY-backed shell: tears the whole process group down when adbd closes.
            String out = conn.exec(snippet, 18_000L);
            record("launched via adb port " + attempt.port + ": " + (out == null ? "" : out.trim()));
        } catch (Exception e) {
            String kind = SetupRules.classifyAdbError(e);
            lastDiagnosis = "auth_revoked".equals(kind) ? "auth_revoked" : "error";
            setLast("connect/launch failed (" + kind + "): " + e.getMessage());
            Log.w(TAG, "launch", e);
            return;
        }
        // The daemon retries its bind for up to 30 s if a previous instance is still shutting down.
        long deadline = System.currentTimeMillis() + 45_000L;
        while (running && System.currentTimeMillis() < deadline) {
            if (healthOk()) {
                lastDiagnosis = "ok";
                RecoveryNotifier.clear(this);
                setLast(healthSummary());
                return;
            }
            try {
                Thread.sleep(750L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        lastDiagnosis = "error";
        setLast("daemon launched but /health did not come up in 45s");
    }

    private AdbLaunchAttempt connectForLaunch(AgentConfig config) {
        Set<Integer> candidates = new LinkedHashSet<>();
        int livePort = AdbDiscovery.latestConnectPort();
        if (livePort > 0) candidates.add(livePort);
        if (config.lastConnectPort > 0) candidates.add(config.lastConnectPort);

        String lastKind = "no_port";
        for (int port : candidates) {
            AdbLaunchAttempt res = openAdbOnce(port, 4_500);
            if (res.connection != null || "auth_revoked".equals(res.diagnosis)) return res;
            if ("refused".equals(res.diagnosis)) AdbDiscovery.clearConnectPort(port);
            lastKind = res.diagnosis;
        }
        int discovered = AdbDiscovery.waitForConnectPort(this, 12_000L, true);
        if (discovered > 0) {
            AdbLaunchAttempt res = openAdbOnce(discovered, 8_000);
            if (res.connection != null || "auth_revoked".equals(res.diagnosis)) return res;
            lastKind = res.diagnosis;
        }
        return new AdbLaunchAttempt(null, 0, "no_port".equals(lastKind) ? "no_port" : lastKind,
                "no wireless-debug connect port (open Wireless debugging)");
    }

    private AdbLaunchAttempt openAdbOnce(int port, int timeoutMs) {
        if (key == null || port <= 0) return new AdbLaunchAttempt(null, 0, "no_port", "no port");
        try {
            AdbConnection conn = AdbConnection.connect("127.0.0.1", port, key, timeoutMs);
            return new AdbLaunchAttempt(conn, port, "ok", "");
        } catch (Exception e) {
            String kind = SetupRules.classifyAdbError(e);
            if (port != ADB_TCP_PORT) {
                record("adb :" + port + " failed (" + kind + "): " + e.getMessage());
            }
            if ("auth_revoked".equals(kind)) {
                return new AdbLaunchAttempt(null, port, "auth_revoked",
                        "Android rejected our Wireless debugging key; pair with a new 6-digit code");
            }
            return new AdbLaunchAttempt(null, port, kind, "adb :" + port + " " + kind);
        }
    }

    private static final class AdbLaunchAttempt {
        final AdbConnection connection;
        final int port;
        final String diagnosis;
        final String logMessage;

        AdbLaunchAttempt(AdbConnection connection, int port, String diagnosis, String logMessage) {
            this.connection = connection;
            this.port = port;
            this.diagnosis = diagnosis;
            this.logMessage = logMessage;
        }
    }

    private boolean healthOk() {
        try {
            String raw = SimpleHttp.get("http://127.0.0.1:" + AgentConfig.load(this).daemonPort + "/health", 2000, null);
            Map<String, Object> h = Json.object(raw);
            // Another service on the same port (an older agent build) must not count as "up".
            return SetupRules.isCurrentDaemon(h);
        } catch (Exception e) {
            return false;
        }
    }

    private String healthSummary() {
        try {
            String raw = SimpleHttp.get("http://127.0.0.1:" + AgentConfig.load(this).daemonPort + "/health", 2000, null);
            Map<String, Object> h = Json.object(raw);
            Object relay = h.get("relay");
            String relayBit = "relay off";
            if (relay instanceof Map) {
                Map<?, ?> r = (Map<?, ?>) relay;
                relayBit = Boolean.TRUE.equals(r.get("connected"))
                        ? ("relay " + String.valueOf(r.get("registeredName")))
                        : "relay idle";
            }
            return "uid " + h.get("uid") + " · " + relayBit;
        } catch (Exception e) {
            return "daemon up? " + e.getMessage();
        }
    }

    private void ensurePolling(AgentConfig config) {
        if (!config.hasAssistant()) return;
        try {
            String endpoint = "http://127.0.0.1:" + config.daemonPort;
            Map<String, Object> health = Json.object(SimpleHttp.get(endpoint + "/health", 2000, null));
            Object relay = health.get("relay");
            if (relay instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) relay).get("polling"))) return;
            String body = Json.stringify(Json.mapOf("relayUrl", config.relayUrl,
                    "deviceToken", config.deviceToken, "deviceName", config.deviceName,
                    "deviceId", config.deviceId));
            SimpleHttp.postJsonOnce(endpoint + "/configure", body, 4000, config.localToken);
            setLast("Restarted the phone's server connection");
        } catch (Exception error) {
            Log.w(TAG, "relay poll recovery", error);
            setLast("Phone connection needs a retry");
        }
    }

    private boolean wirelessDebuggingOff() {
        try {
            return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Android turns Wireless debugging off whenever Wi-Fi drops. The daemon grants
     * this app WRITE_SECURE_SETTINGS, so turn it back on when Wi-Fi is up. Callers
     * only do this on an access point Android already trusts (it then enables
     * silently) or after the owner tapped Reconnect.
     */
    private long lastWirelessAttempt;
    private long lastWirelessCycleAt;
    private static final long WIRELESS_CYCLE_COOLDOWN_MS = 120_000L;

    private boolean cycleWirelessDebugging(boolean ownerAsked) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!ownerAsked && lastWirelessCycleAt != 0 && now - lastWirelessCycleAt < WIRELESS_CYCLE_COOLDOWN_MS) return false;
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return false;
            lastWirelessCycleAt = now;
            record("cycling stuck Wireless debugging (1->0->1)" + (ownerAsked ? " (owner tapped Reconnect)" : ""));
            int stalePort = AdbDiscovery.latestConnectPort();
            if (stalePort > 0) AdbDiscovery.clearConnectPort(stalePort);
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
            for (int i = 0; i < 8 && !wirelessDebuggingOff(); i++) Thread.sleep(350L);
            Thread.sleep(400L);
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
            for (int i = 0; i < 10 && wirelessDebuggingOff(); i++) Thread.sleep(500L);
            boolean on = !wirelessDebuggingOff();
            if (on) AdbDiscovery.refresh(this);
            record(on ? "Wireless debugging cycle completed" : "Wireless debugging did not return after cycle");
            return on;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "wireless debugging cycle", e);
            return false;
        }
    }

    private boolean turnOnWirelessDebugging(boolean ownerAsked) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!ownerAsked && lastWirelessAttempt != 0 && now - lastWirelessAttempt < 15_000L) return false;
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return false;
            lastWirelessAttempt = now;
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
            for (int i = 0; i < 10 && wirelessDebuggingOff(); i++) Thread.sleep(500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "wireless debugging", e);
            return false;
        }
        boolean on = !wirelessDebuggingOff();
        record((on ? "turned Wireless debugging back on" : "Wireless debugging did not turn on") + (ownerAsked ? " (owner tapped Reconnect)" : " (approved access point)"));
        return on;
    }

    void wake(String reason) {
        synchronized (wake) {
            wakeRequested = true;
            wakeReason = reason;
            wake.notifyAll();
        }
    }

    private volatile String wakeReason;

    private String waitForWake(long ms) throws InterruptedException {
        synchronized (wake) {
            long until = android.os.SystemClock.elapsedRealtime() + ms;
            while (!wakeRequested) {
                long left = until - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) return "periodic check";
                wake.wait(left);
            }
            wakeRequested = false;
            return wakeReason == null ? "woken" : wakeReason;
        }
    }

    /**
     * Hold the daemon's /watch stream open. The kernel closes it the instant the
     * daemon dies (adbd stopped, crash), so recovery starts in about a second
     * instead of at the next 30-second check.
     */
    private void startWatcher() {
        if (watcher != null && watcher.isAlive()) return;
        watcher = new Thread(() -> {
            while (running) {
                AgentConfig config = AgentConfig.load(this);
                boolean opened = false;
                try (java.net.Socket socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", config.daemonPort), 2_000);
                    socket.setSoTimeout(45_000);
                    socket.getOutputStream().write(("GET /watch HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer "
                            + config.localToken + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                    String status = in.readLine();
                    if (status != null && status.contains(" 200 ")) {
                        opened = true;
                        while (running && in.readLine() != null) { /* heartbeat */ }
                    }
                } catch (Exception ignored) {
                }
                if (!running) return;
                if (opened) {
                    record("daemon connection closed");
                    wake("daemon connection closed");
                }
                try {
                    Thread.sleep(opened ? 1_000L : 5_000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "backstage-daemon-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private android.database.ContentObserver debugObserver;
    private android.content.BroadcastReceiver usbReceiver;
    private String lastUsb;

    /**
     * Record USB and debugging changes. init stops adbd (and the daemon in its process
     * group) within milliseconds of the trigger, so only this process, which survives,
     * can log what came just before a daemon death.
     */
    private void watchDebugState() {
        if (debugObserver != null) return;
        try {
            debugObserver = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange, android.net.Uri uri) {
                    String key = uri == null ? "?" : uri.getLastPathSegment();
                    String value = Settings.Global.getString(getContentResolver(), key);
                    record("setting " + key + " = " + value);
                    if ("adb_wifi_enabled".equals(key) && "1".equals(value)) {
                        AdbDiscovery.refresh(AgentService.this);
                        wake("wireless debugging turned on");
                    }
                }
            };
            for (String key : new String[]{"adb_enabled", "adb_wifi_enabled", "development_settings_enabled"})
                getContentResolver().registerContentObserver(Settings.Global.getUriFor(key), false, debugObserver);
            usbReceiver = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context context, Intent intent) {
                    android.os.Bundle extras = intent.getExtras();
                    java.util.TreeSet<String> on = new java.util.TreeSet<>();
                    if (extras != null) for (String key : extras.keySet()) if (extras.get(key) instanceof Boolean b && b) on.add(key);
                    String state = String.join(",", on);
                    if (state.equals(lastUsb)) return;
                    lastUsb = state;
                    record("usb state: " + (state.isEmpty() ? "none" : state));
                }
            };
            android.content.IntentFilter usb = new android.content.IntentFilter("android.hardware.usb.action.USB_STATE");
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, usb, RECEIVER_NOT_EXPORTED);
            else registerReceiver(usbReceiver, usb);
        } catch (RuntimeException e) {
            Log.w(TAG, "debug state watch", e);
        }
    }

    /** A new or changed network can make relaunch possible; check at once instead of waiting. */
    private void watchNetwork() {
        if (networkCallback != null) return;
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            if (cm == null) return;
            networkCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) { wake("network available"); }
                @Override public void onLost(android.net.Network network) { wake("network lost"); }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            networkCallback = null;
            Log.w(TAG, "network callback", e);
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Cannot request from a Service; the activity asks. Foreground notif still works.
        }
    }

    private void setLast(String text) {
        AgentActivity.lastStatus = text;
        record(text);
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFY_ID, notification(text));
        } catch (RuntimeException ignored) {
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, AgentActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String snippet;
        if (!AgentConfig.load(this).accessEnabled) snippet = "Pausing access. Open Backstage to check progress.";
        else if ("location".equals(text)||locationThread!=null) snippet = "Getting your requested location.";
        else if (text != null && text.startsWith("uid ") && !text.contains("idle")) snippet = "Connected. Tap to manage or pause access.";
        else snippet = "Connecting your phone. Tap for status and help.";
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(BuildInfo.PRODUCT)
                .setContentText(snippet)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Pause access",
                        PendingIntent.getService(this, 7, new Intent(this, AgentService.class).setAction("com.androidbackstage.STOP"),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build())
                .setOngoing(true)
                .build();
    }

    private void record(String line) {
        SupervisorLog.append(this, line);
        File log = new File(getFilesDir(), LOG_NAME.getName());
        try (FileWriter w = new FileWriter(log, true)) {
            w.write(Instant.now() + " " + line + "\n");
        } catch (Exception ignored) {
        }
        try {
            if (log.length() > 80_000L) {
                List<String> lines = java.nio.file.Files.readAllLines(log.toPath());
                int from = Math.max(0, lines.size() - 80);
                java.nio.file.Files.write(log.toPath(), lines.subList(from, lines.size()));
            }
        } catch (Exception ignored) {
        }
    }

    static String tailLog(android.content.Context context, int n) {
        File log = new File(context.getFilesDir(), LOG_NAME.getName());
        if (!log.isFile()) return "";
        try {
            List<String> lines = java.nio.file.Files.readAllLines(log.toPath());
            int from = Math.max(0, lines.size() - n);
            List<String> tail = new ArrayList<>(lines.subList(from, lines.size()));
            return String.join("\n", tail);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroy() {
        AgentRequests.stop();
        running = false;
        if (watcher != null) watcher.interrupt();
        if (networkCallback != null) {
            try { getSystemService(android.net.ConnectivityManager.class).unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
            networkCallback = null;
        }
        if (debugObserver != null) { try { getContentResolver().unregisterContentObserver(debugObserver); } catch (RuntimeException ignored) { } debugObserver = null; }
        if (usbReceiver != null) { try { unregisterReceiver(usbReceiver); } catch (RuntimeException ignored) { } usbReceiver = null; }
        activeService = null;
        cancelLocation();
        AdbDiscovery.release(mdnsListener);
        if (worker != null) worker.interrupt();
        BootReceiver.scheduleRetry(this);
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) { running = false; cancelLocation(); stopSelf(); }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
