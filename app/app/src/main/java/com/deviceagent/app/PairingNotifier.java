package com.deviceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;

/**
 * Heads-up pairing prompt so the user can type the 6-digit code while
 * Developer options is in front (same idea as Shizuku's wireless pairing UI).
 */
public final class PairingNotifier {
    public static final String CHANNEL = "pairing";
    public static final int ID = 2;
    public static final String KEY_CODE = "pairing_code";

    private PairingNotifier() {}

    private static PendingIntent openApp(Context context) {
        return PendingIntent.getActivity(context, 42, new Intent(context, AgentActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static PendingIntent openCodeEntry(Context context, int port) {
        Intent intent = new Intent(context, AgentActivity.class);
        intent.setAction(AgentActivity.OPEN_PAIRING_INPUT);
        intent.putExtra(PairingCodeReceiver.EXTRA_PORT, port);
        return PendingIntent.getActivity(context, 43, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Wireless pairing", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Enter the 6-digit Wireless debugging pairing code");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    public static void showPairing(Context context, int port) {
        ensureChannel(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent intent = new Intent(context, PairingCodeReceiver.class);
        intent.setAction(PairingCodeReceiver.ACTION);
        intent.putExtra(PairingCodeReceiver.EXTRA_PORT, port);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, port, intent, flags);

        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel("6-digit pairing code")
                .build();
        Notification.Action action = new Notification.Action.Builder(
                android.R.drawable.ic_input_add, "Enter code", pi)
                .addRemoteInput(input)
                .build();

        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Finish Backstage setup")
                .setContentText("Tap Enter code and use the 6 digits shown in Android Settings.")
                .setContentIntent(openCodeEntry(context, port))
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .addAction(action)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_edit, "Enter in app", openCodeEntry(context, port)).build())
                .setAutoCancel(false)
                .build();
        try { nm.notify(ID, n); }
        catch (SecurityException ignored) { /* Notification access is optional; manual pairing remains available. */ }
    }

    public static void showResult(Context context, boolean ok, String message) {
        ensureChannel(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(ok
                        ? R.drawable.ic_notification
                        : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? "Phone access approved" : "Try a new Android pairing code")
                .setContentIntent(openApp(context))
                .setStyle(new Notification.BigTextStyle().bigText(message == null ? "" : message))
                .setContentText(message == null ? "" : message)
                .setAutoCancel(true)
                .build();
        try { nm.notify(ID, n); }
        catch (SecurityException ignored) { /* Notification access is optional; manual pairing remains available. */ }
    }
}
