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
 * Persistent inbox for items the user shares to Backstage from Android's Share sheet.
 */
public final class SharedInbox {
    private static final int MAX_ITEMS = 50;
    private final Path file;
    private final Deque<Map<String, Object>> items = new ArrayDeque<>();

    public SharedInbox(Path file) {
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
                    if (item instanceof Map<?, ?> m) items.addLast(new LinkedHashMap<>((Map<String, Object>) m));
                }
                while (items.size() > MAX_ITEMS) items.removeLast();
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized Map<String, Object> add(String text, String subject, String mimeType, String sourceApp, List<Map<String, Object>> files) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", "shr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        row.put("receivedAt", System.currentTimeMillis());
        if (mimeType != null && !mimeType.isBlank()) row.put("mimeType", mimeType);
        if (subject != null && !subject.isBlank()) row.put("subject", subject);
        if (text != null && !text.isBlank()) row.put("text", text);
        if (sourceApp != null && !sourceApp.isBlank()) row.put("sourceApp", sourceApp);
        if (files != null && !files.isEmpty()) row.put("files", files);
        items.addFirst(row);
        while (items.size() > MAX_ITEMS) items.removeLast();
        persist();
        return new LinkedHashMap<>(row);
    }

    public synchronized List<Map<String, Object>> list(int limit) {
        int cap = Math.max(1, Math.min(MAX_ITEMS, limit));
        List<Map<String, Object>> out = new ArrayList<>(Math.min(cap, items.size()));
        for (Map<String, Object> item : items) {
            if (out.size() >= cap) break;
            out.add(new LinkedHashMap<>(item));
        }
        return out;
    }

    public synchronized boolean remove(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        boolean removed = items.removeIf(m -> itemId.equals(m.get("itemId")));
        if (removed) persist();
        return removed;
    }

    public synchronized void clear() {
        items.clear();
        persist();
    }

    private void persist() {
        if (file == null) return;
        try {
            com.deviceagent.crypto.PrivateState.write(file, Json.stringify(new ArrayList<>(items)));
        } catch (Exception ignored) {
        }
    }
}
