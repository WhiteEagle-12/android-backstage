package com.deviceagent.adb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers Wireless debugging pairing and connect ports via mDNS
 * ({@code _adb-tls-pairing._tcp.} / {@code _adb-tls-connect._tcp.}).
 *
 * Holds a {@link WifiManager.MulticastLock} while active so Wi-Fi chipsets do not
 * silently filter mDNS packets. Uses {@code registerServiceInfoCallback} on API 34+
 * for live updates without single-slot resolve contention, and timeout-guarded
 * serialized {@code resolveService} on API 30-33.
 */
public final class AdbMdns {
    public static final String TLS_PAIRING = "_adb-tls-pairing._tcp.";
    public static final String TLS_CONNECT = "_adb-tls-connect._tcp.";
    private static final long RESOLVE_WATCHDOG_MS = 4_000L;

    public interface Listener {
        void onPairingService(String host, int port);

        void onConnectService(String host, int port);

        void onLost(String type);
    }

    private final Context appContext;
    private final NsdManager nsd;
    private final Listener listener;
    private final Handler handler;
    private final ArrayDeque<PendingResolve> resolveQueue = new ArrayDeque<>();
    private final Set<String> queuedKeys = new HashSet<>();
    private final Map<String, Object> infoCallbacks = new HashMap<>();
    private WifiManager.MulticastLock multicastLock;
    private boolean resolving;
    private int resolveGeneration;
    private boolean started;
    private DiscoveryListener pairingDiscovery;
    private DiscoveryListener connectDiscovery;

    public AdbMdns(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.nsd = (NsdManager) this.appContext.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
        Looper looper = Looper.myLooper();
        if (looper == null) {
            looper = Looper.getMainLooper();
        }
        this.handler = new Handler(looper);
    }

    public void start() {
        synchronized (this) {
            if (started || nsd == null) {
                return;
            }
            started = true;
            acquireMulticastLockLocked();
        }
        startDiscovery(TLS_PAIRING);
        startDiscovery(TLS_CONNECT);
    }

    public void stop() {
        DiscoveryListener pair;
        DiscoveryListener conn;
        List<Object> callbacks;
        synchronized (this) {
            started = false;
            resolveQueue.clear();
            queuedKeys.clear();
            resolving = false;
            resolveGeneration++;
            pair = pairingDiscovery;
            pairingDiscovery = null;
            conn = connectDiscovery;
            connectDiscovery = null;
            callbacks = new ArrayList<>(infoCallbacks.values());
            infoCallbacks.clear();
            releaseMulticastLockLocked();
        }
        handler.removeCallbacksAndMessages(null);
        stopDiscovery(pair);
        stopDiscovery(conn);
        unregisterCallbacks(callbacks);
    }

    /** Re-issue mDNS discovery immediately (e.g. after Wi-Fi reconnect or Wireless debugging toggle). */
    public void restart() {
        stop();
        start();
    }

    private void acquireMulticastLockLocked() {
        try {
            if (multicastLock == null) {
                WifiManager wifi = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    multicastLock = wifi.createMulticastLock("backstage-adb-mdns");
                    multicastLock.setReferenceCounted(false);
                }
            }
            if (multicastLock != null && !multicastLock.isHeld()) {
                multicastLock.acquire();
            }
        } catch (RuntimeException ignored) {
            // MulticastLock is best-effort; NsdManager still works on many builds without it.
        }
    }

    private void releaseMulticastLockLocked() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void startDiscovery(String type) {
        if (nsd == null) return;
        DiscoveryListener dl = new DiscoveryListener(type);
        synchronized (this) {
            if (!started) return;
            if (TLS_PAIRING.equals(type)) {
                pairingDiscovery = dl;
            } else {
                connectDiscovery = dl;
            }
        }
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl);
        } catch (RuntimeException e) {
            scheduleRetry(type);
        }
    }

    private void stopDiscovery(DiscoveryListener dl) {
        if (dl == null || nsd == null) {
            return;
        }
        try {
            nsd.stopServiceDiscovery(dl);
        } catch (RuntimeException ignored) {
        }
    }

    private void scheduleRetry(String type) {
        handler.postDelayed(
                () -> {
                    synchronized (AdbMdns.this) {
                        if (!started) {
                            return;
                        }
                    }
                    startDiscovery(type);
                },
                2000);
    }

    private void onServiceFoundInternal(NsdServiceInfo info, String type) {
        if (Build.VERSION.SDK_INT >= 34) {
            registerServiceInfoCallbackApi34(info, type);
        } else {
            enqueueResolveLegacy(info, type);
        }
    }

    private void onServiceLostInternal(NsdServiceInfo info, String type) {
        if (Build.VERSION.SDK_INT >= 34 && info != null) {
            String key = serviceKey(type, info);
            Object cb;
            synchronized (this) {
                cb = infoCallbacks.remove(key);
            }
            if (cb != null) {
                unregisterCallbackApi34(cb);
            }
        }
        listener.onLost(type);
    }

    private void registerServiceInfoCallbackApi34(NsdServiceInfo info, String type) {
        if (Build.VERSION.SDK_INT < 34 || nsd == null || info == null) return;
        String key = serviceKey(type, info);
        NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
            @Override
            public void onServiceInfoCallbackRegistrationFailed(int errorCode) {
                synchronized (AdbMdns.this) {
                    infoCallbacks.remove(key, this);
                }
            }

            @Override
            public void onServiceUpdated(NsdServiceInfo serviceInfo) {
                onResolved(type, serviceInfo);
            }

            @Override
            public void onServiceLost() {
                synchronized (AdbMdns.this) {
                    infoCallbacks.remove(key, this);
                }
                listener.onLost(type);
            }

            @Override
            public void onServiceInfoCallbackUnregistered() {}
        };
        Object previous;
        synchronized (this) {
            if (!started) return;
            previous = infoCallbacks.put(key, cb);
        }
        if (previous != null) {
            unregisterCallbackApi34(previous);
        }
        try {
            nsd.registerServiceInfoCallback(info, handler::post, cb);
        } catch (RuntimeException e) {
            synchronized (this) {
                infoCallbacks.remove(key, cb);
            }
            enqueueResolveLegacy(info, type);
        }
    }

    private void unregisterCallbacks(List<Object> callbacks) {
        if (Build.VERSION.SDK_INT < 34 || nsd == null) return;
        for (Object cb : callbacks) {
            unregisterCallbackApi34(cb);
        }
    }

    private void unregisterCallbackApi34(Object cb) {
        if (Build.VERSION.SDK_INT < 34 || nsd == null || !(cb instanceof NsdManager.ServiceInfoCallback)) {
            return;
        }
        try {
            nsd.unregisterServiceInfoCallback((NsdManager.ServiceInfoCallback) cb);
        } catch (RuntimeException ignored) {
        }
    }

    private void enqueueResolveLegacy(NsdServiceInfo info, String type) {
        synchronized (this) {
            if (!started || info == null) {
                return;
            }
            String key = serviceKey(type, info);
            if (!queuedKeys.add(key)) {
                return;
            }
            resolveQueue.add(new PendingResolve(info, type, key));
            pumpResolveLocked();
        }
    }

    @SuppressWarnings("deprecation")
    private void pumpResolveLocked() {
        if (resolving || resolveQueue.isEmpty() || nsd == null) {
            return;
        }
        resolving = true;
        final int gen = ++resolveGeneration;
        PendingResolve next = resolveQueue.removeFirst();
        queuedKeys.remove(next.key);
        handler.postDelayed(() -> {
            synchronized (AdbMdns.this) {
                if (!started || !resolving || resolveGeneration != gen) return;
                resolving = false;
                pumpResolveLocked();
            }
        }, RESOLVE_WATCHDOG_MS);
        try {
            nsd.resolveService(next.info, new ResolveListenerImpl(next.type, gen));
        } catch (RuntimeException e) {
            resolving = false;
            handler.postDelayed(
                    () -> {
                        synchronized (AdbMdns.this) {
                            if (!started) return;
                            resolving = false;
                            pumpResolveLocked();
                        }
                    },
                    250);
        }
    }

    @SuppressWarnings("deprecation")
    private void onResolved(String type, NsdServiceInfo info) {
        if (info == null) return;
        boolean local = false;
        if (Build.VERSION.SDK_INT >= 34) {
            for (InetAddress addr : info.getHostAddresses()) {
                if (LocalAdbAddress.isLocal(addr)) {
                    local = true;
                    break;
                }
            }
        }
        if (!local) {
            InetAddress addr = info.getHost();
            local = LocalAdbAddress.isLocal(addr);
        }
        if (!local) {
            return;
        }
        int port = info.getPort();
        if (port <= 0 || port >= 65536) return;
        String host = "127.0.0.1";
        if (TLS_PAIRING.equals(type)) {
            listener.onPairingService(host, port);
        } else {
            listener.onConnectService(host, port);
        }
    }

    private static String serviceKey(String type, NsdServiceInfo info) {
        String name = info == null || info.getServiceName() == null ? "" : info.getServiceName();
        return type + ":" + name;
    }

    private final class DiscoveryListener implements NsdManager.DiscoveryListener {
        private final String type;

        DiscoveryListener(String type) {
            this.type = type;
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            scheduleRetry(type);
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {}

        @Override
        public void onDiscoveryStarted(String serviceType) {}

        @Override
        public void onDiscoveryStopped(String serviceType) {}

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            onServiceFoundInternal(serviceInfo, type);
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            onServiceLostInternal(serviceInfo, type);
        }
    }

    @SuppressWarnings("deprecation")
    private final class ResolveListenerImpl implements NsdManager.ResolveListener {
        private final String type;
        private final int generation;

        ResolveListenerImpl(String type, int generation) {
            this.type = type;
            this.generation = generation;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            synchronized (AdbMdns.this) {
                if (resolveGeneration != generation) return;
                resolving = false;
                pumpResolveLocked();
            }
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            try {
                onResolved(type, serviceInfo);
            } finally {
                synchronized (AdbMdns.this) {
                    if (resolveGeneration == generation) {
                        resolving = false;
                        pumpResolveLocked();
                    }
                }
            }
        }
    }

    private static final class PendingResolve {
        final NsdServiceInfo info;
        final String type;
        final String key;

        PendingResolve(NsdServiceInfo info, String type, String key) {
            this.info = info;
            this.type = type;
            this.key = key;
        }
    }
}
