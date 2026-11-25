package com.deviceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * One quiet notification for the rare case the phone link cannot come back by
 * itself. It never pops up or makes a sound; tapping it reconnects, which is the
 * only time Android may show its own Wireless debugging approval.
 */
final class RecoveryNotifier {
    static final String CHANNEL = "backstage-reconnect";
    /** The earlier high-priority channel nagged on every Wi-Fi change. */
    private static final String OLD_CHANNEL = "backstage-recovery-v2";
    static final String OPEN_WIRELESS = "com.androidbackstage.OPEN_WIRELESS";
    static final int ID = 3;

    private RecoveryNotifier() {}

    static void showOpenApp(Context context) {
        Intent open = new Intent(context, AgentActivity.class).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        show(context, "Open Backstage to restore access", "Backstage could not restart in the background.",
                PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    static void showReconnect(Context context, String reason) {
        Intent reconnect = new Intent(context, AgentService.class).setAction(AgentService.ACTION_RECONNECT);
        show(context, "Phone access is offline", reason + " Tap this notification to reconnect.",
                PendingIntent.getForegroundService(context, ID, reconnect, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static void show(Context context, String title, String body, PendingIntent action) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.deleteNotificationChannel(OLD_CHANNEL);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Connection status", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(action).setOnlyAlertOnce(true).setAutoCancel(true).build();
        try { manager.notify(ID, notification); }
        catch (SecurityException ignored) { /* The app shows the same recovery action. */ }
    }

    static void clear(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(ID);
    }
}
