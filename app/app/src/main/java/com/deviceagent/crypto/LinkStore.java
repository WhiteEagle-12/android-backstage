package com.deviceagent.crypto;

import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.daemon.DaemonLog;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe map of {@code agentId → linkKey} plus call/result counters.
 * Persists security state synchronously before execution or emission.
 */
public final class LinkStore {
    public static final class Unauthorized extends Exception {
        public Unauthorized(String reason) {
            super(reason);
        }
    }


    private final DaemonConfig config;
    public final TaskPermits permits;
    public final RequestJournal journal;
    private final Map<String, Live> links = new LinkedHashMap<>();
    private long lastSaveAt;
    private boolean dirty;

    public LinkStore(DaemonConfig config) {
        this.config = config == null ? new DaemonConfig() : config;
        permits = new TaskPermits(this.config.configPath);
        journal = new RequestJournal(this.config.configPath);
        reloadFromConfig();
    }

    public synchronized void reloadFromConfig() {
        links.clear();
        if (config.links == null) return;
        for (DaemonConfig.Link row : config.links) {
            if (row == null || row.agentId == null || row.agentId.isBlank()
                    || row.linkKey == null || row.linkKey.isBlank()) {
                continue;
            }
            try {
                links.put(row.agentId, Live.from(row));
            } catch (RuntimeException e) {
                DaemonLog.print("skipping malformed link " + row.agentId + ": " + e.getMessage());
            }
        }
    }

    public synchronized String statePath(String name) {
        return java.nio.file.Paths.get(config.configPath).resolveSibling(name).toString();
    }

    public synchronized List<String> agentIds() {
        return new ArrayList<>(links.keySet());
    }

    public synchronized boolean isEmpty() {
        return links.isEmpty();
    }

    public synchronized void addLink(String agentId, String linkKeyB64) {
        addLink(agentId, linkKeyB64, "0", Long.toUnsignedString(System.currentTimeMillis()));
    }

    public synchronized void addLink(String agentId, String linkKeyB64, String lastCallCounter, String resultCounter) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (linkKeyB64 == null || linkKeyB64.isBlank()) {
            throw new IllegalArgumentException("linkKey is required");
        }
        byte[] key = LinkPairing.unb64url(linkKeyB64.trim());
        if (key.length != 32) {
            throw new IllegalArgumentException("linkKey must decode to 32 bytes");
        }
        String id = agentId.trim();
        if (config.revokedLinks.contains(DaemonConfig.fingerprint(id, linkKeyB64.trim())))
            throw new IllegalArgumentException("This link was revoked. Pair again with a new key.");
        Live existing = links.get(id);
        if (existing != null && java.security.MessageDigest.isEqual(existing.linkKey, key)) {
            long last = parseCounter(lastCallCounter, existing.lastCallCounter);
            if (Long.compareUnsigned(last, existing.lastCallCounter) > 0) { existing.lastCallCounter = last; existing.floor = last; existing.recentCalls.clear(); }
            long result = parseCounter(resultCounter, existing.resultCounter);
            if (Long.compareUnsigned(result, existing.resultCounter) > 0) existing.resultCounter = result;
            flushLocked();
            return;
        }
        Live live = new Live();
        live.agentId = id;
        live.linkKey = key;
        live.callKey = LinkPairing.callKey(live.linkKey);
        live.resultKey = LinkPairing.resultKey(live.linkKey);
        live.lastCallCounter = parseCounter(lastCallCounter, 0L);
        live.floor = live.lastCallCounter;
        long now = System.currentTimeMillis();
        live.resultCounter = parseCounter(resultCounter, now);
        if (live.resultCounter == 0L) live.resultCounter = now;
        links.put(live.agentId, live);
        flushLocked();
    }

    public synchronized void forget(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        Live removed = links.remove(agentId.trim());
        if (removed != null) config.revokedLinks.add(DaemonConfig.fingerprint(removed.agentId, LinkPairing.b64url(removed.linkKey)));
        flushLocked();
    }

    /**
     * Decrypt a call envelope. Rejects unknown {@code from}, bad GCM tags, and
     * {@code n <= lastCallCounter}. Persists the accepted counter (debounced).
     */
    public synchronized byte[] openCall(Envelope env) throws Unauthorized {
        if (env == null || env.from == null || env.from.isBlank()) {
            throw new Unauthorized("unknown agent");
        }
        Live live = links.get(env.from);
        if (live == null) {
            throw new Unauthorized("unknown agent");
        }
        byte[] plaintext;
        try {
            plaintext = Envelope.open(live.callKey, env);
        } catch (SecurityException e) {
            throw new Unauthorized("bad tag");
        }
        long n;
        try {
            n = env.nLong();
        } catch (RuntimeException e) {
            throw new Unauthorized("malformed envelope");
        }
        // Several calls from agents sharing a link can be in flight at once, so
        // they may open slightly out of order. Accept each counter once: above
        // the counter saved at startup, inside the window, and not seen before.
        if (Long.compareUnsigned(n, live.floor) <= 0
                || Long.compareUnsigned(n, live.lastCallCounter - CALL_WINDOW) <= 0 && Long.compareUnsigned(live.lastCallCounter, CALL_WINDOW) > 0
                || !live.recentCalls.add(n)) {
            throw new Unauthorized("replay");
        }
        if (Long.compareUnsigned(n, live.lastCallCounter) > 0) live.lastCallCounter = n;
        while (live.recentCalls.size() > CALL_WINDOW) live.recentCalls.pollFirst();
        persistDebouncedLocked();
        return plaintext;
    }

    /** Seal a result toward {@code agentId}. {@code from} on the envelope is {@code deviceId}. */
    public synchronized Envelope sealResult(String agentId, String deviceId, byte[] plaintext)
            throws Unauthorized {
        if (agentId == null || agentId.isBlank()) throw new Unauthorized("unknown agent");
        Live live = links.get(agentId);
        if (live == null) throw new Unauthorized("unknown agent");
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalStateException("deviceId is required to seal a result");
        }
        if (live.resultCounter == Long.MAX_VALUE) {
            throw new IllegalStateException("result counter overflow");
        }
        live.resultCounter++;
        long n = live.resultCounter;
        persistDebouncedLocked();
        return Envelope.sealRandom(live.resultKey, deviceId, n, plaintext);
    }

    public synchronized void flush() {
        flushLocked();
    }

    private void persistDebouncedLocked() {
        // Durability precedes accepting a command or emitting a result nonce.
        flushLocked();
    }

    private void flushLocked() {
        syncToConfigLocked();
        try {
            config.save();
            lastSaveAt = System.currentTimeMillis();
            dirty = false;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist link state; refusing operation", e);
        }
    }

    private void syncToConfigLocked() {
        List<DaemonConfig.Link> rows = new ArrayList<>();
        for (Live live : links.values()) {
            DaemonConfig.Link row = new DaemonConfig.Link();
            row.agentId = live.agentId;
            row.linkKey = LinkPairing.b64url(live.linkKey);
            row.lastCallCounter = Long.toUnsignedString(live.lastCallCounter);
            row.resultCounter = Long.toUnsignedString(live.resultCounter);
            rows.add(row);
        }
        config.links = rows;
    }

    private static long parseCounter(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Long.parseUnsignedLong(s.trim());
        } catch (NumberFormatException e) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e2) {
                return fallback;
            }
        }
    }

    private static final long CALL_WINDOW = 1024L;

    private static final class Live {
        /** Counters at or below the value saved before this process started were used already. */
        long floor;
        final java.util.TreeSet<Long> recentCalls = new java.util.TreeSet<>(Long::compareUnsigned);
        String agentId;
        byte[] linkKey;
        byte[] callKey;
        byte[] resultKey;
        long lastCallCounter;
        long resultCounter;

        static Live from(DaemonConfig.Link row) {
            Live live = new Live();
            live.agentId = row.agentId.trim();
            live.linkKey = LinkPairing.unb64url(row.linkKey.trim());
            if (live.linkKey.length != 32) {
                throw new IllegalArgumentException("linkKey must decode to 32 bytes");
            }
            live.callKey = LinkPairing.callKey(live.linkKey);
            live.resultKey = LinkPairing.resultKey(live.linkKey);
            live.lastCallCounter = parseCounter(row.lastCallCounter, 0L);
            live.floor = live.lastCallCounter;
            live.resultCounter = parseCounter(row.resultCounter, 0L);
            return live;
        }
    }
}
