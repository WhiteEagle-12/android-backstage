package com.deviceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Summons the owner back when an assistant claims the pending invitation.
 * Without this, the user pastes the setup message into their agent and has
 * nothing telling them when to return to approve the six check digits.
 */
public final class InvitationNotifier {
    public static final String CHANNEL = "invitations";
    public static final int ID = 4;

    private InvitationNotifier() {}

    public static void ensureChannel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Agent requests", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("An assistant is waiting for your approval to connect");
        nm.createNotificationChannel(ch);
    }

    public static void showApproval(Context context, String agentName) {
        ensureChannel(context);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(context, 43,
                new Intent(context, AgentActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String name = agentName == null || agentName.isBlank() ? "An assistant" : agentName;
        Notification n = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(name + " wants access")
                .setContentText("Tap to compare the six digits and approve.")
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(false)
                .build();
        try { nm.notify(ID, n); }
        catch (SecurityException ignored) { /* Approval still waits in the app. */ }
    }

    public static void cancel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }
}
