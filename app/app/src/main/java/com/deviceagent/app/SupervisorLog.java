package com.deviceagent.app;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.time.Instant;

/**
 * The supervisor's record of daemon deaths and relaunches, kept in the app's
 * external files folder so the shell daemon (and agents through it) can read it
 * on a release build.
 */
final class SupervisorLog {
    private static final long LIMIT = 256 * 1024;

    private SupervisorLog() {}

    static synchronized void append(Context context, String line) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            File log = new File(dir, "supervisor.log");
            if (log.length() > LIMIT) {
                File old = new File(dir, "supervisor.log.1");
                if (old.exists() && !old.delete()) return;
                if (!log.renameTo(old)) return;
            }
            try (FileWriter w = new FileWriter(log, true)) {
                w.write(Instant.now() + " " + line.replace('\n', ' ') + "\n");
            }
        } catch (Exception ignored) {
        }
    }
}
