package com.deviceagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    static final String ACTION_RECOVER = "com.androidbackstage.RECOVER_SERVICE";
    private static final int RETRY_REQUEST = 11;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_USER_UNLOCKED:
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case ACTION_RECOVER:
                break;
            default:
                return;
        }
        AgentConfig config = AgentConfig.load(context);
        if (!SetupRules.shouldStart(config.accessEnabled, config.hasAssistant(), config.paired)) return;
        try {
            context.startForegroundService(new Intent(context, AgentService.class));
            AgentConfig.setBootBlocked(context, false);
        } catch (RuntimeException error) {
            android.util.Log.e("Backstage", "Could not restore supervision", error);
            AgentConfig.setBootBlocked(context, true);
            RecoveryNotifier.showOpenApp(context);
            scheduleRetry(context);
        }
    }

    private static android.app.PendingIntent retryIntent(Context context) {
        return android.app.PendingIntent.getBroadcast(context, RETRY_REQUEST,
                new Intent(context, BootReceiver.class).setAction(ACTION_RECOVER),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleRetry(Context context) {
        AgentConfig config = AgentConfig.load(context);
        if (!SetupRules.shouldStart(config.accessEnabled, config.hasAssistant(), config.paired)) return;
        android.app.AlarmManager alarms = context.getSystemService(android.app.AlarmManager.class);
        if (alarms == null) return;
        try { alarms.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 120_000L, retryIntent(context)); }
        catch (RuntimeException error) { android.util.Log.e("Backstage", "Could not schedule supervision retry", error); }
    }

    static void cancelRetry(Context context) {
        android.app.AlarmManager alarms = context.getSystemService(android.app.AlarmManager.class);
        if (alarms != null) alarms.cancel(retryIntent(context));
    }
}
