package com.deviceagent.app;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import com.deviceagent.adb.AdbKey;
import com.deviceagent.adb.AdbPairing;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PairingCodeReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.deviceagent.app.ACTION_SUBMIT_PAIRING_CODE";
    public static final String EXTRA_PORT = "port";
    private static final String TAG = "Backstage";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int extraPort = intent.getIntExtra(EXTRA_PORT, 0);
        String rawCode = null;
        Bundle extras = RemoteInput.getResultsFromIntent(intent);
        if (extras != null) {
            CharSequence cs = extras.getCharSequence(PairingNotifier.KEY_CODE);
            if (cs != null) rawCode = cs.toString();
        }
        if ((rawCode == null || rawCode.isBlank()) && intent.hasExtra(PairingNotifier.KEY_CODE)) {
            rawCode = intent.getStringExtra(PairingNotifier.KEY_CODE);
        }
        String code = SetupRules.normalizePairingDigits(rawCode);
        Context app = context.getApplicationContext();
        Set<Integer> candidatePorts = new LinkedHashSet<>();
        int livePort = AdbDiscovery.latestPairPort();
        if (livePort > 0) candidatePorts.add(livePort);
        if (extraPort > 0) candidatePorts.add(extraPort);
        int savedPort = AgentConfig.load(app).lastPairPort;
        if (savedPort > 0) candidatePorts.add(savedPort);

        if (candidatePorts.isEmpty() || code.isEmpty()) {
            PairingNotifier.showResult(context, false, "Open Pair device with pairing code in Android Settings, then enter its 6-digit code here.");
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                AdbKey key = AdbKey.loadOrCreate(new File(app.getFilesDir(), "adb"), "backstage@" + Build.MODEL);
                int pairedPort = 0;
                Exception lastError = null;
                for (int port : candidatePorts) {
                    try {
                        AdbPairing.pair("127.0.0.1", port, code, key, 15_000);
                        pairedPort = port;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                    }
                }
                if (pairedPort <= 0) {
                    throw lastError != null ? lastError : new IllegalStateException("Pairing failed");
                }
                AgentConfig.saveAdbState(app, null, pairedPort, true);
                AgentService.lastDiagnosis = "starting";
                AgentConfig config = AgentConfig.load(app);
                boolean started = false;
                if (SetupRules.shouldStart(config.accessEnabled, config.hasAssistant(), true)) {
                    try {
                        app.startForegroundService(new Intent(app, AgentService.class).setAction(AgentService.ACTION_RECONNECT));
                        started = true;
                    } catch (RuntimeException ignored) {
                        /* Some Android versions require a tap to return to the app. */
                    }
                }
                PairingNotifier.showResult(app, true, started ? "Starting your connection. Tap to open Backstage." : "Tap to return to Backstage and finish connecting.");
            } catch (Exception e) {
                Log.w(TAG, "pair failed", e);
                PairingNotifier.showResult(app, false, "Open a new pairing code in Wireless debugging and try again.");
            } finally {
                pending.finish();
            }
        }, "backstage-pair").start();
    }
}
