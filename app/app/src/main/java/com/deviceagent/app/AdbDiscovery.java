package com.deviceagent.app;

import android.content.Context;
import android.util.Log;
import com.deviceagent.adb.AdbMdns;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide coordinator for {@link AdbMdns}.
 *
 * Keeps a single NsdManager discovery pair active across AgentActivity,
 * AgentService, and one-shot connect waits, avoiding Android's per-app
 * NsdManager listener limit.
 */
public final class AdbDiscovery {
    private static final String TAG = "Backstage";
    private static final Object LOCK = new Object();
    private static final Set<AdbMdns.Listener> LISTENERS = new LinkedHashSet<>();

    private static AdbMdns mdns;
    private static Context appContext;
    private static volatile int latestConnectPort;
    private static volatile int latestPairPort;

    private AdbDiscovery() {}

    public static void acquire(Context context, AdbMdns.Listener listener) {
        if (context == null || listener == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            appContext = app;
            LISTENERS.add(listener);
            ensureStartedLocked(app);
        }
    }

    public static void release(AdbMdns.Listener listener) {
        if (listener == null) return;
        AdbMdns toStop = null;
        synchronized (LOCK) {
            LISTENERS.remove(listener);
            if (LISTENERS.isEmpty() && mdns != null) {
                toStop = mdns;
                mdns = null;
            }
        }
        if (toStop != null) {
            try {
                toStop.stop();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Re-kick discovery after a Wi-Fi reconnect or Wireless debugging toggle. */
    public static void refresh(Context context) {
        AdbMdns current;
        synchronized (LOCK) {
            if (context != null) appContext = context.getApplicationContext();
            current = mdns;
        }
        if (current != null) {
            try {
                current.restart();
            } catch (Throwable t) {
                Log.w(TAG, "mdns restart failed", t);
            }
        }
    }

    public static int latestConnectPort() {
        return latestConnectPort;
    }

    public static int latestPairPort() {
        return latestPairPort;
    }

    public static void clearConnectPort(int stalePort) {
        if (stalePort > 0 && latestConnectPort == stalePort) {
            latestConnectPort = 0;
        }
    }

    /**
     * Wait up to {@code timeoutMs} for a Wireless debugging connect port using the shared
     * mDNS session instead of opening a competing NsdManager instance.
     */
    public static int waitForConnectPort(Context context, long timeoutMs, boolean forceRefresh) {
        int current = latestConnectPort;
        if (!forceRefresh && current > 0) return current;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger found = new AtomicInteger(0);
        AdbMdns.Listener waiter = new AdbMdns.Listener() {
            @Override
            public void onPairingService(String host, int port) {}

            @Override
            public void onConnectService(String host, int port) {
                found.set(port);
                latch.countDown();
            }

            @Override
            public void onLost(String type) {}
        };
        acquire(context, waiter);
        try {
            if (forceRefresh) {
                refresh(context);
            }
            latch.await(Math.max(500L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release(waiter);
        }
        int port = found.get();
        return port > 0 ? port : latestConnectPort;
    }

    private static void ensureStartedLocked(Context app) {
        if (mdns != null) return;
        try {
            mdns = new AdbMdns(app, new AdbMdns.Listener() {
                @Override
                public void onPairingService(String host, int port) {
                    latestPairPort = port;
                    Context ctx = appContext;
                    if (ctx != null) AgentConfig.saveAdbState(ctx, null, port, null);
                    for (AdbMdns.Listener l : snapshotListeners()) {
                        try {
                            l.onPairingService(host, port);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }

                @Override
                public void onConnectService(String host, int port) {
                    latestConnectPort = port;
                    Context ctx = appContext;
                    if (ctx != null) AgentConfig.saveAdbState(ctx, port, null, null);
                    for (AdbMdns.Listener l : snapshotListeners()) {
                        try {
                            l.onConnectService(host, port);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }

                @Override
                public void onLost(String type) {
                    if (AdbMdns.TLS_PAIRING.equals(type)) latestPairPort = 0;
                    else if (AdbMdns.TLS_CONNECT.equals(type)) latestConnectPort = 0;
                    for (AdbMdns.Listener l : snapshotListeners()) {
                        try {
                            l.onLost(type);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            });
            mdns.start();
        } catch (Throwable t) {
            mdns = null;
            Log.w(TAG, "AdbDiscovery start failed", t);
        }
    }

    private static List<AdbMdns.Listener> snapshotListeners() {
        synchronized (LOCK) {
            return new ArrayList<>(LISTENERS);
        }
    }
}
