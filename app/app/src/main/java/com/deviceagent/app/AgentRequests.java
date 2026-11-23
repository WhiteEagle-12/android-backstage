package com.deviceagent.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agents ask for access from the computer; this phone shows each request as a
 * notification with Allow and Deny, so connecting needs one tap and no copied
 * code.
 */
public final class AgentRequests {
    private static final String ACTION = "com.deviceagent.app.AGENT_REQUEST";
    private static Thread watcher;
    private static volatile boolean running;

    private AgentRequests() {}

    /** Start watching for requests; safe to call repeatedly. */
    public static synchronized void start(Context context) {
        if (watcher != null && watcher.isAlive()) return;
        Context app = context.getApplicationContext();
        running = true;
        watcher = new Thread(() -> loop(app), "backstage-requests");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static synchronized void stop() { running = false; if (watcher != null) watcher.interrupt(); watcher = null; }

    private static void loop(Context app) {
        android.util.Log.i("BackstageRequests", "watching for agent requests");
        AccountClient client = null; String server = null;
        Set<String> shown = new HashSet<>(); long backoff = 5_000;
        while (running) {
            try {
                AgentConfig config = AgentConfig.load(app);
                if (!config.hasAssistant() || !SetupRules.sameServer(config.relayUrl, SetupRules.DEFAULT_RELAY)) { Thread.sleep(60_000); continue; }
                if (client == null || !config.relayUrl.equals(server)) { client = new AccountClient(config.relayUrl); server = config.relayUrl; }
                client.signIn(config.links);
                List<AccountClient.Request> pending = client.requests(true);
                Set<String> now = new HashSet<>();
                for (AccountClient.Request r : pending) { now.add(r.id); if (shown.add(r.id)) show(app, r); }
                for (String old : new HashSet<>(shown)) if (!now.contains(old)) { cancel(app, old); shown.remove(old); }
                backoff = 5_000;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                android.util.Log.w("BackstageRequests", "request check failed: " + e);
                try { Thread.sleep(backoff); } catch (InterruptedException interrupted) { return; }
                backoff = Math.min(backoff * 2, 120_000);
            }
        }
    }

    private static int notificationId(String requestId) { return 1000 + (requestId.hashCode() & 0x7fff); }

    private static void show(Context app, AccountClient.Request r) {
        android.util.Log.i("BackstageRequests", "showing request " + r.id);
        InvitationNotifier.ensureChannel(app);
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm == null) return;
        PendingIntent open = PendingIntent.getActivity(app, notificationId(r.id), new Intent(app, AgentActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(app, InvitationNotifier.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(r.names + " wants access")
                .setContentText("Check that your computer shows " + r.check + ".")
                .setStyle(new Notification.BigTextStyle().bigText("Check that your computer shows " + r.check
                        + ". Allowing gives access to apps and commands on all your devices. You can pause or remove it in Backstage."))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(false);
        if (r.expiresAt > 0) builder.setTimeoutAfter(Math.max(1_000, r.expiresAt - System.currentTimeMillis()));
        builder.addAction(action(app, r.id, false, "Deny"));
        builder.addAction(action(app, r.id, true, "Allow"));
        nm.notify(notificationId(r.id), builder.build());
    }

    private static Notification.Action action(Context app, String requestId, boolean allow, String label) {
        Intent intent = new Intent(app, Receiver.class).setAction(ACTION).putExtra("request", requestId).putExtra("allow", allow);
        PendingIntent pending = PendingIntent.getBroadcast(app, notificationId(requestId) * 2 + (allow ? 1 : 0), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder builder = new Notification.Action.Builder(null, label, pending);
        // Approving from the lock screen requires unlocking first.
        if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true);
        return builder.build();
    }

    static void cancel(Context app, String requestId) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(notificationId(requestId));
    }

    /** Decide a request from the app or a notification. Returns a message for the user. */
    static String decide(Context app, String requestId, boolean allow) {
        AgentConfig config = AgentConfig.load(app);
        AccountClient client = new AccountClient(config.relayUrl);
        try {
            client.signIn(config.links);
            client.decide(requestId, allow);
            cancel(app, requestId);
            return allow ? "Access allowed. Return to your agent." : "Request denied.";
        } catch (Exception e) {
            return AccountClient.explain(e);
        }
    }

    /** Notification buttons. Works while the app is closed. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION.equals(intent.getAction())) return;
            String requestId = intent.getStringExtra("request");
            boolean allow = intent.getBooleanExtra("allow", false);
            if (requestId == null) return;
            PendingResult result = goAsync();
            Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    String message = decide(app, requestId, allow);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show());
                } finally { result.finish(); }
            }, "backstage-decide").start();
        }
    }
}
