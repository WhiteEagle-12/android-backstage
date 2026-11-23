package com.deviceagent.shell;

import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.tools.Json;
import com.deviceagent.tools.PrivacyFence;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lightweight background watcher for phone-to-agent event triggers:
 * filtered notifications (never for blocked apps) and battery thresholds.
 */
public final class EventWatch {
    private static final long POLL_MS = 15_000L;
    private final DaemonConfig config;
    private final InteractionHost interaction;
    private final Consumer<Map<String, Object>> emit;
    private final Set<String> seenNotifications = new HashSet<>();
    private volatile boolean running;
    private boolean batteryNotified;
    private Thread thread;

    public EventWatch(DaemonConfig config, InteractionHost interaction, Consumer<Map<String, Object>> emit) {
        this.config = config;
        this.interaction = interaction;
        this.emit = emit;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "backstage-event-watch");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void nudge() {
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                tick();
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void tick() {
        Map<String, Object> rules = config == null ? null : config.eventRules;
        if (rules == null || rules.isEmpty() || Boolean.FALSE.equals(rules.get("enabled"))) return;
        checkBattery(rules);
        checkNotifications(rules);
    }

    private void checkBattery(Map<String, Object> rules) {
        Object raw = rules.get("batteryBelow");
        if (!(raw instanceof Number n)) return;
        int threshold = n.intValue();
        int level = readBatteryPercent();
        if (level < 0) return;
        if (level <= threshold && !batteryNotified) {
            batteryNotified = true;
            emit.accept(Json.mapOf("type", "battery_low", "payload", Json.mapOf("percent", level, "threshold", threshold)));
        } else if (level >= threshold + 5) {
            batteryNotified = false;
        }
    }

    @SuppressWarnings("unchecked")
    private void checkNotifications(Map<String, Object> rules) {
        List<?> pkgs = rules.get("notificationPackages") instanceof List<?> l ? l : List.of();
        String senderFilter = rules.get("senderContains") instanceof String s ? s.toLowerCase(Locale.ROOT) : "";
        String textFilter = rules.get("textContains") instanceof String s ? s.toLowerCase(Locale.ROOT) : "";
        if (pkgs.isEmpty() && senderFilter.isEmpty() && textFilter.isEmpty()) return;
        Map<String, Object> res = interaction.handle("notification", Json.mapOf("operation", "list", "max", 30));
        if (!Boolean.TRUE.equals(res.get("ok")) || !(res.get("notifications") instanceof List<?> list)) return;
        Set<String> currentKeys = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> n = (Map<String, Object>) raw;
            String pkg = String.valueOf(n.getOrDefault("package", ""));
            if (PrivacyFence.isGloballyBlocked(pkg)) continue;
            if (!pkgs.isEmpty() && !pkgs.contains(pkg)) continue;
            String title = String.valueOf(n.getOrDefault("title", ""));
            String text = String.valueOf(n.getOrDefault("text", ""));
            if (!senderFilter.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(senderFilter)) continue;
            if (!textFilter.isEmpty() && !(title + " " + text).toLowerCase(Locale.ROOT).contains(textFilter)) continue;
            String key = pkg + ":" + n.get("postTime") + ":" + title;
            currentKeys.add(key);
            if (seenNotifications.add(key)) {
                emit.accept(Json.mapOf("type", "notification_received", "payload", Json.mapOf(
                        "package", pkg, "title", title, "text", text,
                        "notificationId", n.get("notificationId"), "postTime", n.get("postTime"))));
            }
        }
        seenNotifications.retainAll(currentKeys);
    }

    private static int readBatteryPercent() {
        try (BufferedReader br = new BufferedReader(new FileReader("/sys/class/power_supply/battery/capacity"))) {
            String line = br.readLine();
            return line == null ? -1 : Integer.parseInt(line.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
