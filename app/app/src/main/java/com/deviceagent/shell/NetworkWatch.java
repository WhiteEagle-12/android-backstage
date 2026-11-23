package com.deviceagent.shell;

import com.deviceagent.daemon.Lifeline;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Notices network changes (Wi-Fi to cellular, a new access point address, Wi-Fi back)
 * without any permission: it compares interface addresses every two seconds.
 * Reconnect loops sleep through {@link #sleep} so a change ends their backoff at once,
 * and listeners can abort stalled sockets immediately.
 */
public final class NetworkWatch {
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final Object SIGNAL = new Object();
    private static final Set<Runnable> LISTENERS = ConcurrentHashMap.newKeySet();
    private static volatile String signature = "";
    private static Thread thread;

    private NetworkWatch() {}

    public static void addListener(Runnable listener) {
        if (listener != null) LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) LISTENERS.remove(listener);
    }

    public static synchronized void start() {
        if (thread != null) return;
        signature = read();
        Lifeline.event("network: " + (signature.isEmpty() ? "none" : signature));
        thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException e) {
                    return;
                }
                String next = read();
                if (!next.equals(signature)) {
                    Lifeline.event("network: " + (signature.isEmpty() ? "none" : signature) + " -> " + (next.isEmpty() ? "none" : next));
                    signature = next;
                    EPOCH.incrementAndGet();
                    synchronized (SIGNAL) { SIGNAL.notifyAll(); }
                    for (Runnable listener : LISTENERS) {
                        try {
                            listener.run();
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }
        }, "backstage-network-watch");
        thread.setDaemon(true);
        thread.start();
    }

    public static long epoch() {
        return EPOCH.get();
    }

    public static String signature() {
        return signature;
    }

    /**
     * Sleep up to {@code ms}, returning early when the network changes.
     * Returns false only when the thread was interrupted.
     */
    public static boolean sleep(long ms) {
        long start = EPOCH.get();
        long until = System.currentTimeMillis() + ms;
        synchronized (SIGNAL) {
            while (EPOCH.get() == start) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    SIGNAL.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /** Up interfaces with IPv4 addresses, e.g. {@code wlan0=192.0.2.10 tun0=100.64.0.10}. */
    static String read() {
        List<String> parts = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address) parts.add(nic.getName() + "=" + address.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(parts);
        return String.join(" ", parts);
    }
}
