package com.deviceagent.shell;

import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * {@link ProcessBuilder} exec in this process. Used to live on
 * {@code ShellUserService} (the Shizuku user-service stub); the daemon now
 * runs as uid 2000 via {@code app_process}, so this is a plain helper.
 */
public final class LocalShell {
    private LocalShell() {}

    public static Bundle run(String command, byte[] stdin, long timeoutMs) {
        com.deviceagent.tools.ActionDeadline.check();
        Bundle out = new Bundle();
        Process process = null;
        try {
            process = new ProcessBuilder("setsid", "sh", "-c", command == null ? "" : command).start();
            if (stdin != null && stdin.length > 0) {
                try (OutputStream sink = process.getOutputStream()) {
                    sink.write(stdin);
                    sink.flush();
                }
            } else {
                process.getOutputStream().close();
            }
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outPump = pump(process.getInputStream(), stdout);
            Thread errPump = pump(process.getErrorStream(), stderr);
            long wait = Math.max(1L, Math.min(timeoutMs,com.deviceagent.tools.ActionDeadline.remaining()));
            boolean finished = process.waitFor(wait, TimeUnit.MILLISECONDS);
            if (!finished) {
                killGroup(process);process.destroyForcibly();
                process.waitFor(2L, TimeUnit.SECONDS);
                out.putInt("exit", -1);
                out.putString("error", "timeout");
            } else {
                out.putInt("exit", process.exitValue());
            }
            join(outPump);
            join(errPump);
            out.putByteArray("stdout", stdout.toByteArray());
            out.putByteArray("stderr", stderr.toByteArray());
        } catch (Exception e) {
            if(process!=null)killGroup(process);
            out.putInt("exit", -1);
            out.putString("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            out.putByteArray("stdout", new byte[0]);
            out.putByteArray("stderr", new byte[0]);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return out;
    }
    private static void killGroup(Process process){
        try{long pid=((Number)Process.class.getMethod("pid").invoke(process)).longValue();if(pid>1&&pid<=Integer.MAX_VALUE)android.system.Os.kill(-(int)pid,android.system.OsConstants.SIGKILL);}catch(Exception ignored){}
    }

    private static final int MAX_STREAM_BYTES = 1024 * 1024;
    private static final byte[] TRUNCATED_SUFFIX = "\n... [output truncated at 1 MiB]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                int read;
                int kept = 0;
                boolean truncated = false;
                while ((read = in.read(buffer)) != -1) {
                    if (kept < MAX_STREAM_BYTES) {
                        int take = Math.min(read, MAX_STREAM_BYTES - kept);
                        out.write(buffer, 0, take);
                        kept += take;
                        if (take < read) truncated = true;
                    } else {
                        truncated = true;
                    }
                }
                if (truncated) out.write(TRUNCATED_SUFFIX);
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t) {
        try {
            t.join(1500L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
