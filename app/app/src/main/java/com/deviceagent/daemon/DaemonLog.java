package com.deviceagent.daemon;

import java.time.Instant;

/** Timestamped stderr lines so the coordinator can read daemon.log. */
public final class DaemonLog {
    private DaemonLog() {}

    public static void print(String msg) {
        System.err.println(Instant.now() + " [backstage] " + msg);
        System.err.flush();
    }

    public static void print(String msg, Throwable t) {
        print(msg + (t == null || t.getMessage() == null ? "" : ": " + t.getMessage()));
        if (t != null) t.printStackTrace(System.err);
        System.err.flush();
    }
}
