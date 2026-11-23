package com.deviceagent.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded on-device activity timeline of agent actions. Stores only metadata
 * (agent, tool, target app/summary, outcome, time), never typed text or secrets.
 */
public final class ActivityTimeline {
    private static final int MAX_ENTRIES = 200;
    private final Path file;
    private final Deque<Map<String, Object>> entries = new ArrayDeque<>();

    public ActivityTimeline(Path file) {
        this.file = file;
        load();
    }

    @SuppressWarnings("unchecked")
    private synchronized void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Object parsed = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        entries.addLast(new LinkedHashMap<>((Map<String, Object>) m));
                    }
                }
                while (entries.size() > MAX_ENTRIES) entries.removeLast();
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void record(String agentId, String tool, String target, String outcome, String code, Integer display) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "act_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        row.put("timeMs", System.currentTimeMillis());
        row.put("agentId", agentId == null ? "local" : agentId);
        row.put("tool", tool == null ? "" : tool);
        if (target != null && !target.isBlank()) row.put("target", target);
        row.put("outcome", outcome == null ? "succeeded" : outcome);
        if (code != null && !code.isBlank()) row.put("code", code);
        if (display != null) row.put("display", display);
        entries.addFirst(row);
        while (entries.size() > MAX_ENTRIES) entries.removeLast();
        persist();
    }

    public synchronized List<Map<String, Object>> recent(int limit) {
        int cap = Math.max(1, Math.min(MAX_ENTRIES, limit));
        List<Map<String, Object>> out = new ArrayList<>(Math.min(cap, entries.size()));
        for (Map<String, Object> row : entries) {
            if (out.size() >= cap) break;
            out.add(new LinkedHashMap<>(row));
        }
        return out;
    }

    public synchronized void clear() {
        entries.clear();
        persist();
    }

    private void persist() {
        if (file == null) return;
        try {
            com.deviceagent.crypto.PrivateState.write(file, Json.stringify(new ArrayList<>(entries)));
        } catch (Exception ignored) {
        }
    }
}
