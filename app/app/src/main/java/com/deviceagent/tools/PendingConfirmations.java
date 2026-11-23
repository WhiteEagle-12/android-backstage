package com.deviceagent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory coordinator for "Prepare & Confirm" prompts. An agent pauses a task,
 * sends a preview + plain-language prompt to the phone, and blocks until the user
 * picks Approve, Take Over on Screen (handoff), or Cancel.
 */
public final class PendingConfirmations {
    private static final Set<String> VALID_DECISIONS = Set.of("approved", "handoff", "cancelled");

    public static final class Item {
        public final String confirmId;
        public final String sessionId;
        public final int displayId;
        public final String agentId;
        public final String title;
        public final String summary;
        public final String appPackage;
        public final String screenshotBase64;
        public final String screenshotMime;
        public final long createdAt;
        public final long expiresAt;
        public volatile String decision = "pending";

        Item(String confirmId, String sessionId, int displayId, String agentId, String title,
             String summary, String appPackage, String screenshotBase64, String screenshotMime,
             long createdAt, long expiresAt) {
            this.confirmId = confirmId;
            this.sessionId = sessionId;
            this.displayId = displayId;
            this.agentId = agentId;
            this.title = title;
            this.summary = summary;
            this.appPackage = appPackage;
            this.screenshotBase64 = screenshotBase64;
            this.screenshotMime = screenshotMime;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public Map<String, Object> toMap(boolean includeImage) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("confirmId", confirmId);
            m.put("sessionId", sessionId);
            m.put("displayId", displayId);
            m.put("agentId", agentId);
            m.put("title", title);
            m.put("summary", summary);
            if (appPackage != null && !appPackage.isEmpty()) m.put("package", appPackage);
            m.put("createdAt", createdAt);
            m.put("expiresAt", expiresAt);
            m.put("decision", decision);
            if (includeImage && screenshotBase64 != null && !screenshotBase64.isEmpty()) {
                m.put("screenshotBase64", screenshotBase64);
                m.put("screenshotMime", screenshotMime == null ? "image/webp" : screenshotMime);
            }
            return m;
        }
    }

    private final Map<String, Item> pending = new LinkedHashMap<>();

    public synchronized Item create(String sessionId, int displayId, String agentId, String title,
                                    String summary, String appPackage, String screenshotBase64,
                                    String screenshotMime, long timeoutMs) {
        pruneLocked();
        long now = System.currentTimeMillis();
        String id = "cfm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Item item = new Item(id, sessionId, displayId, agentId == null ? "local" : agentId,
                title, summary, appPackage, screenshotBase64, screenshotMime, now, now + timeoutMs);
        pending.put(id, item);
        return item;
    }

    public String awaitDecision(String confirmId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(1_000L, timeoutMs);
        synchronized (this) {
            Item item = pending.get(confirmId);
            if (item == null) return "cancelled";
            while ("pending".equals(item.decision)) {
                long remaining = Math.min(deadline - System.currentTimeMillis(), ActionDeadline.remaining());
                if (remaining <= 0) {
                    item.decision = "timeout";
                    pending.remove(confirmId);
                    return "timeout";
                }
                try {
                    wait(Math.min(500L, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    item.decision = "cancelled";
                    pending.remove(confirmId);
                    return "cancelled";
                }
            }
            String result = item.decision;
            pending.remove(confirmId);
            return result;
        }
    }

    public synchronized boolean decide(String confirmId, String decision) {
        if (confirmId == null || !VALID_DECISIONS.contains(decision)) return false;
        Item item = pending.get(confirmId);
        if (item == null || !"pending".equals(item.decision)) return false;
        item.decision = decision;
        notifyAll();
        return true;
    }

    public synchronized List<Map<String, Object>> list(boolean includeImage) {
        pruneLocked();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Item item : pending.values()) {
            if ("pending".equals(item.decision)) out.add(item.toMap(includeImage));
        }
        return out;
    }

    private void pruneLocked() {
        long now = System.currentTimeMillis();
        pending.values().removeIf(item -> !"pending".equals(item.decision) || item.expiresAt <= now);
    }
}
