package com.deviceagent.rpc;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Job ids this phone got from the relay. The relay sends a job again when its receipt
 * was lost, so an id seen before must not run twice. Running ids are never forgotten;
 * finished ids are kept a few minutes and capped in number.
 */
public final class ReceivedJobs {
    public enum Seen { NEW, RUNNING, DONE }

    static final long KEEP_DONE_MS = 5 * 60_000L;
    static final int MAX_DONE = 512;

    private final LongSupplier clock;
    /** id → finish time, or -1 while running. Insertion order is receive order. */
    private final LinkedHashMap<String, Long> ids = new LinkedHashMap<>();

    public ReceivedJobs(LongSupplier clock) {
        this.clock = clock;
    }

    /** Records the id; NEW means run it. */
    public synchronized Seen receive(String id) {
        prune();
        Long state = ids.get(id);
        if (state == null) {
            ids.put(id, -1L);
            return Seen.NEW;
        }
        return state < 0 ? Seen.RUNNING : Seen.DONE;
    }

    public synchronized void done(String id) {
        if (ids.containsKey(id)) ids.put(id, clock.getAsLong());
    }

    private void prune() {
        long now = clock.getAsLong();
        int done = 0;
        for (long state : ids.values()) if (state >= 0) done++;
        for (Iterator<Map.Entry<String, Long>> it = ids.entrySet().iterator(); it.hasNext(); ) {
            long state = it.next().getValue();
            if (state < 0) continue;
            if (now - state > KEEP_DONE_MS || done > MAX_DONE) {
                it.remove();
                done--;
            }
        }
    }
}
