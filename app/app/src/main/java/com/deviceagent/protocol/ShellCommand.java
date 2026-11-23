package com.deviceagent.protocol;

import java.util.ArrayList;
import java.util.List;

public final class ShellCommand {
    private ShellCommand() {}

    public static String quote(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "''";
        }
        boolean safe = arg.chars().allMatch(c ->
                (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.' || c == '/' || c == ':' || c == '@' || c == '=' || c == '%');
        if (safe) {
            return arg;
        }
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    public static String join(String... args) {
        List<String> quoted = new ArrayList<>();
        for (String arg : args) {
            quoted.add(quote(arg));
        }
        return String.join(" ", quoted);
    }

    public static String amStart(String packageName, String activity) {
        return amStart(packageName, activity, null);
    }

    /**
     * Launch onto a specific display. Off the default display we also ask for a
     * separate task (NEW_TASK is implied by am start), so an instance the user
     * already has open on the phone screen stays where it is instead of being
     * pulled onto the virtual display.
     */
    public static String amStart(String packageName, String activity, Integer displayId) {
        if (displayId == null) return join("am", "start", "-n", component(packageName, activity));
        return join("am", "start", "--display", Integer.toString(displayId),
                "--activity-multiple-task", "-n", component(packageName, activity));
    }

    /** {@code --display N} fragment for am start, or empty for the default display. */
    public static String displayOption(Integer displayId) {
        return displayId == null ? "" : " --display " + displayId;
    }

    /** {@code -d N} fragment for input, or empty for the default display. */
    static String inputDisplay(Integer displayId) {
        return displayId == null ? "" : " -d " + displayId;
    }

    public static String forceStop(String packageName) {
        return join("am", "force-stop", packageName);
    }

    public static String dumpsysPackage(String packageName) {
        return join("dumpsys", "package", packageName);
    }

    public static String screenshot() {
        return "screencap -p";
    }

    public static String uiDump() {
        return uiDump(false);
    }

    /**
     * {@code --windows} dumps every window on every display (wrapped in
     * {@code <display id="N">}), which is the only way to see a virtual display;
     * the plain dump only covers the active window on the phone screen.
     */
    public static String uiDump(boolean allWindows) {
        String flag = allWindows ? "--windows " : "";
        return "rm -f /data/local/tmp/window_dump.xml; uiautomator dump " + flag
                + "/data/local/tmp/window_dump.xml >/dev/null 2>&1; cat /data/local/tmp/window_dump.xml 2>/dev/null";
    }

    public static String wakeDisplay() {
        return "input keyevent 224; wm dismiss-keyguard >/dev/null 2>&1; sleep 0.2";
    }

    public static String tap(int x, int y) {
        return tap(x, y, null);
    }

    public static String tap(int x, int y, Integer displayId) {
        return "input" + inputDisplay(displayId) + " " + join("tap", Integer.toString(x), Integer.toString(y));
    }

    public static String swipe(int x1, int y1, int x2, int y2, int durationMs) {
        return swipe(x1, y1, x2, y2, durationMs, null);
    }

    public static String swipe(int x1, int y1, int x2, int y2, int durationMs, Integer displayId) {
        return "input" + inputDisplay(displayId) + " " + join("swipe", Integer.toString(x1), Integer.toString(y1),
                Integer.toString(x2), Integer.toString(y2), Integer.toString(durationMs));
    }

    public static String keyevent(String key) {
        return keyevent(key, null);
    }

    public static String keyevent(String key, Integer displayId) {
        return "input" + inputDisplay(displayId) + " " + join("keyevent", key);
    }

    /** Every root task with its display, from {@code am stack list}. */
    public static String listTasks() {
        return "am stack list 2>/dev/null";
    }

    /** Move a root task to another display (the hand-off from virtual display to the real screen). */
    public static String moveTaskToDisplay(int rootTaskId, int displayId) {
        return join("am", "display", "move-stack", Integer.toString(rootTaskId), Integer.toString(displayId));
    }

    /** mCurrentFocus of one display's section in {@code dumpsys window displays}. */
    public static String focusOnDisplay(int displayId) {
        return "dumpsys window displays 2>/dev/null | { d=0; while IFS= read -r l; do "
                + "case \"$l\" in *'Display: mDisplayId='*) "
                + "case \"$l\" in *'mDisplayId=" + displayId + "'|*'mDisplayId=" + displayId + "'[!0-9]*) d=1 ;; *) d=0 ;; esac ;; esac; "
                + "if [ \"$d\" = 1 ]; then case \"$l\" in *'mCurrentFocus='*) printf '%s\\n' \"$l\"; break ;; esac; fi; "
                + "done; }";
    }

    /** OEMs may leave mFocusedApp null; popups may omit a window component. */
    public static String focusedAppOnDisplay(int displayId) {
        if (displayId <= 0) throw new IllegalArgumentException("Use a private display");
        return "dumpsys window displays 2>/dev/null | { d=0; while IFS= read -r l; do "
                + "case \"$l\" in *'Display: mDisplayId='*) "
                + "case \"$l\" in *'mDisplayId=" + displayId + "'|*'mDisplayId=" + displayId + "'[!0-9]*) d=1 ;; *) d=0 ;; esac ;; esac; "
                + "if [ \"$d\" = 1 ]; then case \"$l\" in *'mCurrentFocus='*|*'mFocusedApp='*) printf '%s\\n' \"$l\" ;; esac; fi; "
                + "done; }";
    }

    /** The crash ring buffer: one FATAL EXCEPTION record per app crash, survives the app dying. */
    public static String crashLog() {
        return "logcat -d -b crash -v threadtime 2>/dev/null";
    }

    /**
     * system_server's own crash records. Unlike logcat these cannot be silenced
     * by an OEM's log.tag=S, so they are the primary source on locked-down ROMs.
     */
    public static String dropboxCrashes() {
        return "for t in data_app_crash data_app_native_crash data_app_anr; do dumpsys dropbox --print $t 2>/dev/null; done | tail -c 400000";
    }

    /** Instrumentation run with raw status lines so the result can be parsed. */
    public static String instrument(String component, String testClass, String extraArgs) {
        StringBuilder cmd = new StringBuilder("am instrument -w -r");
        if (testClass != null && !testClass.isBlank()) cmd.append(" -e class ").append(quote(testClass));
        if (extraArgs != null && !extraArgs.isBlank()) {
            for (String token : extraArgs.trim().split("\\s+")) {
                if (!token.isEmpty()) cmd.append(' ').append(quote(token));
            }
        }
        return cmd.append(' ').append(quote(component)).toString();
    }

    public static String currentFocus() {
        return "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'";
    }

    public static String uninstall(String packageName) {
        return join("pm", "uninstall", packageName);
    }

    public static String typeText(String text) {
        return typeText(text, null);
    }

    public static String typeText(String text, Integer displayId) {
        return "input" + inputDisplay(displayId) + " " + join("text", text.replace(" ", "%s"));
    }

    public static String install(String remotePath) {
        return join("pm", "install", "-r", "-t", remotePath);
    }

    public static String installRelease(String remotePath) {
        return join("pm", "install", "-r", remotePath);
    }

    public static String getprop(String key) {
        return join("getprop", key);
    }

    public static String id() {
        return "id";
    }

    public static String component(String packageName, String activity) {
        if (activity.startsWith(".")) {
            return packageName + "/" + packageName + activity;
        }
        if (activity.contains("/")) {
            return activity;
        }
        return packageName + "/" + activity;
    }

    public static String requirePackage(String packageName) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9._]+")) {
            throw new IllegalArgumentException("invalid package name: " + packageName);
        }
        return packageName;
    }
}
