package com.deviceagent.daemon;

import android.content.Context;
import android.os.Looper;
import java.lang.reflect.Method;

/**
 * Bootstrap a {@link Context} from an {@code app_process} started as uid 2000.
 * Same path Shizuku's server and scrcpy use: {@code ActivityThread.systemMain()}
 * then {@code createPackageContext}. Hidden-API checks do not apply to shell.
 */
public final class SystemContext {
    private SystemContext() {}

    public static Context create(String packageName) {
        try {
            if (Looper.myLooper() == null && Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
                DaemonLog.print("Looper.prepareMainLooper() ok");
            } else {
                DaemonLog.print("looper already present (main=" + (Looper.getMainLooper() != null) + ")");
            }
        } catch (Throwable t) {
            DaemonLog.print("prepareMainLooper failed (continuing)", t);
        }

        Object activityThread = null;
        Context system = null;
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Method systemMain = cls.getMethod("systemMain");
            activityThread = systemMain.invoke(null);
            DaemonLog.print("ActivityThread.systemMain() = " + activityThread);
            if (activityThread != null) {
                Method getSystemContext = cls.getMethod("getSystemContext");
                system = (Context) getSystemContext.invoke(activityThread);
                DaemonLog.print("getSystemContext() = " + describe(system));
            }
        } catch (Throwable t) {
            DaemonLog.print("ActivityThread.systemMain/getSystemContext failed", t);
        }

        if (system == null) {
            DaemonLog.print("no system context; VirtualDisplayHost will try its own fallback");
            return null;
        }

        if (packageName == null || packageName.isBlank()) {
            DaemonLog.print("using system context (no packageName)");
            return system;
        }

        try {
            int flags = Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY;
            Context pkg = system.createPackageContext(packageName, flags);
            DaemonLog.print("createPackageContext(" + packageName + ") = " + describe(pkg));
            return pkg;
        } catch (Throwable t) {
            DaemonLog.print("createPackageContext(" + packageName + ") failed; falling back to system context", t);
            return system;
        }
    }

    private static String describe(Context c) {
        if (c == null) return "null";
        try {
            return c.getClass().getName() + " pkg=" + c.getPackageName();
        } catch (Throwable t) {
            return c.getClass().getName() + " (getPackageName failed)";
        }
    }
}
