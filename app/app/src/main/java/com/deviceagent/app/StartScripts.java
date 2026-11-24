package com.deviceagent.app;

import android.content.Context;
import com.deviceagent.daemon.Starter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Writes {@code start.sh} into the app's external files dir whenever config changes. */
public final class StartScripts {
    private StartScripts() {}

    public static File file(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, "start.sh");
    }

    public static String oneLiner(Context context) {
        return "adb shell sh /sdcard/Android/data/" + context.getPackageName() + "/files/start.sh";
    }

    public static String configJson(Context context, AgentConfig config) {
        return Starter.configJson(
                config.relayUrl,
                config.deviceToken,
                config.localToken,
                config.deviceName,
                BuildInfo.VERSION,
                apkPath(context),
                context.getPackageName(),
                config.daemonPort,
                config.links,
                config.deviceId,
                config.keepUsbDebugging,
                config.allowShell);
    }

    public static String apkPath(Context context) {
        String src = context.getApplicationInfo().sourceDir;
        return src == null ? "" : src;
    }

    public static void write(Context context, AgentConfig config) {
        try {
            File f = file(context);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            String script = Starter.startScript(apkPath(context), configJson(context, config));
            Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            f.setReadable(true, false);
            f.setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }
}
