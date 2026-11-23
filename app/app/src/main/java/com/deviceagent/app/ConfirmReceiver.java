package com.deviceagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.deviceagent.rpc.SimpleHttp;
import com.deviceagent.tools.Json;

/**
 * Handles Prepare & Confirm prompts from the daemon: posts a high-priority
 * notification with Approve / Take over / Cancel actions, and forwards user
 * taps directly to loopback POST /confirmations/decide.
 */
public final class ConfirmReceiver extends BroadcastReceiver {
    public static final String ACTION_REQUEST = "com.androidbackstage.app.CONFIRM_REQUEST";
    public static final String ACTION_DECIDE = "com.androidbackstage.app.CONFIRM_DECIDE";
    private static final String CHANNEL_ID = "backstage_confirm";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String confirmId = intent.getStringExtra("confirmId");
        if (confirmId == null || confirmId.isBlank()) return;
        if (ACTION_REQUEST.equals(action)) {
            showNotification(context, confirmId,
                    intent.getStringExtra("title"),
                    intent.getStringExtra("summary"));
        } else if (ACTION_DECIDE.equals(action)) {
            String decision = intent.getStringExtra("decision");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(confirmId.hashCode());
            if (decision == null || decision.isBlank()) return;
            final Context appCtx = context.getApplicationContext();
            PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    AgentConfig cfg = AgentConfig.load(appCtx);
                    int port = cfg.daemonPort > 0 ? cfg.daemonPort : 18422;
                    SimpleHttp.postJsonOnce("http://127.0.0.1:" + port + "/confirmations/decide",
                            Json.stringify(Json.mapOf("confirmId", confirmId, "decision", decision)),
                            5_000, cfg.localToken);
                } catch (Exception ignored) {
                } finally {
                    pr.finish();
                }
            }, "backstage-confirm-decide").start();
        }
    }

    private static void showNotification(Context ctx, String confirmId, String title, String summary) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Action confirmations", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);

        Intent open = new Intent(ctx, AgentActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("confirmId", confirmId);
        PendingIntent contentIntent = PendingIntent.getActivity(ctx, confirmId.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title == null || title.isBlank() ? "Confirm action" : title)
                .setContentText(summary == null ? "" : summary)
                .setStyle(new Notification.BigTextStyle().bigText(summary == null ? "" : summary))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .addAction(actionBtn(ctx, confirmId, "approved", "Approve", 1))
                .addAction(actionBtn(ctx, confirmId, "handoff", "Take over", 2))
                .addAction(actionBtn(ctx, confirmId, "cancelled", "Cancel", 3));
        nm.notify(confirmId.hashCode(), b.build());
    }

    private static Notification.Action actionBtn(Context ctx, String confirmId, String decision, String label, int offset) {
        Intent i = new Intent(ctx, ConfirmReceiver.class);
        i.setAction(ACTION_DECIDE);
        i.putExtra("confirmId", confirmId);
        i.putExtra("decision", decision);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, confirmId.hashCode() + offset, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, label, pi).build();
    }
}
