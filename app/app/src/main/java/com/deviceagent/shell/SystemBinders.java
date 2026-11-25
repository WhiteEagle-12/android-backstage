package com.deviceagent.shell;

import java.lang.reflect.Method;

/**
 * Direct binder calls the shell-UID daemon makes without spawning a process:
 * system properties, Wi-Fi connection info and Android's ADB manager. Every
 * call returns a neutral value instead of throwing, because callers poll.
 */
public final class SystemBinders {
    private SystemBinders() {}

    /** The package shell owns; system services check the caller's package against its UID. */
    static final String SHELL_PACKAGE = "com.android.shell";

    public static String prop(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Object value = props.getMethod("get", String.class, String.class).invoke(null, key, "");
            return value == null ? "" : value.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static Object service(String name) {
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            return manager.getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object asInterface(String stubClass, Object binder) throws Exception {
        Class<?> stub = Class.forName(stubClass);
        return stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
    }

    private static Object adb() throws Exception {
        Object binder = service("adb");
        if (binder == null) throw new IllegalStateException("adb service unavailable");
        return asInterface("android.debug.IAdbManager$Stub", binder);
    }

    /** Wireless debugging's TLS port, -1 while it is off, or -2 when the call failed. */
    public static int adbWirelessPort() {
        try {
            Object manager = adb();
            Object value = manager.getClass().getMethod("getAdbWirelessPort").invoke(manager);
            return value instanceof Integer port ? port : -2;
        } catch (Throwable t) {
            return -2;
        }
    }

    /**
     * IAdbManager.allowWirelessDebugging(alwaysAllow=true, bssid): what Android's own
     * "Always allow on this network" button calls. Needs MANAGE_DEBUGGING, which shell
     * holds. Android records the access point as trusted and, when it is the current
     * one, turns Wireless debugging on without showing a dialog.
     */
    public static boolean allowWirelessDebugging(String bssid) {
        try {
            Object manager = adb();
            manager.getClass().getMethod("allowWirelessDebugging", boolean.class, String.class).invoke(manager, true, bssid);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Default USB configuration applied on screen unlock (e.g. "none", "rndis", "mtp").
     * On some OEMs (such as ZTE/RedMagic), tethering ("rndis"/"ncm") causes USB config
     * resets on lock/unlock that stop adbd and kill the daemon.
     */
    public static String usbScreenUnlockedFunctions() {
        try {
            Object binder = service("usb");
            if (binder == null) return "";
            Object manager = asInterface("android.hardware.usb.IUsbManager$Stub", binder);
            Object val = manager.getClass().getMethod("getScreenUnlockedFunctions").invoke(manager);
            if (!(val instanceof Long mask)) return "";
            if (mask == 0L) return "none";
            java.util.List<String> names = new java.util.ArrayList<>();
            if ((mask & (1L << 5)) != 0) names.add("rndis");
            if ((mask & (1L << 10)) != 0) names.add("ncm");
            if ((mask & (1L << 2)) != 0) names.add("mtp");
            if ((mask & (1L << 4)) != 0) names.add("ptp");
            if ((mask & (1L << 3)) != 0) names.add("midi");
            if ((mask & (1L << 7)) != 0) names.add("uvc");
            return names.isEmpty() ? "mask:" + mask : String.join(",", names);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Resets default unlocked USB configuration to No data transfer (FUNCTION_NONE). */
    public static boolean clearUsbScreenUnlockedFunctions() {
        try {
            Object binder = service("usb");
            if (binder != null) {
                Object manager = asInterface("android.hardware.usb.IUsbManager$Stub", binder);
                manager.getClass().getMethod("setScreenUnlockedFunctions", long.class).invoke(manager, 0L);
                return true;
            }
        } catch (Throwable ignored) {
        }
        android.os.Bundle r = LocalShell.run("svc usb setScreenUnlockedFunctions", null, 4_000L);
        return r.getInt("exit", -1) == 0;
    }

    /**
     * Running package/process -> PID map via a single IActivityManager Binder IPC (~1-2 ms),
     * avoiding per-window pidof shell spawns or scanning 1000 /proc entries in Java.
     */
    public static java.util.Map<String, String> runningAppPids() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        try {
            Object binder = service("activity");
            if (binder == null) return out;
            Object am = asInterface("android.app.IActivityManager$Stub", binder);
            Object listObj = am.getClass().getMethod("getRunningAppProcesses").invoke(am);
            if (!(listObj instanceof java.util.List<?> list)) return out;
            for (Object item : list) {
                if (!(item instanceof android.app.ActivityManager.RunningAppProcessInfo info)) continue;
                String pidStr = Integer.toString(info.pid);
                if (info.processName != null && !info.processName.isEmpty()) {
                    out.merge(info.processName, pidStr, (a, b) -> a + " " + b);
                }
                if (info.pkgList != null) {
                    for (String pkg : info.pkgList) {
                        if (pkg != null && !pkg.isEmpty() && !pkg.equals(info.processName)) {
                            out.putIfAbsent(pkg, pidStr);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Top visible activity component (pkg/cls) on the given display via IActivityTaskManager
     * Binder IPC (~1 ms), avoiding `dumpsys window displays | awk` shell spawns on every poll.
     */
    public static String topActivityOnDisplay(int displayId) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            if (atm == null) return "";
            Object listObj = atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(atm, displayId);
            if (!(listObj instanceof java.util.List<?> list)) return "";
            for (Object info : list) {
                if (info == null) continue;
                Class<?> c = info.getClass();
                boolean visible = false;
                try {
                    Object v = c.getField("visible").get(info);
                    visible = Boolean.TRUE.equals(v);
                } catch (NoSuchFieldException ignored) {
                    try {
                        Object v = c.getField("isVisible").get(info);
                        visible = Boolean.TRUE.equals(v);
                    } catch (NoSuchFieldException ignored2) {
                        visible = true;
                    }
                }
                if (!visible) continue;
                Object top = null;
                try { top = c.getField("topActivity").get(info); } catch (NoSuchFieldException ignored) { }
                if (top instanceof android.content.ComponentName cn) {
                    return cn.getPackageName() + "/" + cn.getClassName();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * Checks whether the given process is kernel-frozen via cgroup v2 (`cgroup.freeze`).
     * Returns {@code Boolean.FALSE} only when every matching PID's cgroup explicitly reports 0,
     * {@code Boolean.TRUE} when any PID reports 1, or {@code null} when unknown/unreadable.
     */
    public static Boolean isProcessFrozen(String processName) {
        if (processName == null || processName.isEmpty()) return null;
        try {
            String pids = runningAppPids().get(processName);
            if (pids == null || pids.isEmpty()) return null;
            boolean checked = false;
            for (String pid : pids.split("\\s+")) {
                if (pid.isEmpty()) continue;
                String cgPath = null;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/" + pid + "/cgroup"))) {
                    for (String line; (line = br.readLine()) != null; ) {
                        if (line.startsWith("0::")) {
                            cgPath = line.substring(3).trim();
                            break;
                        }
                    }
                }
                if (cgPath == null || cgPath.isEmpty()) return null;
                String val;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/sys/fs/cgroup" + cgPath + "/cgroup.freeze"))) {
                    val = br.readLine();
                }
                if (val == null) return null;
                val = val.trim();
                if ("1".equals(val)) return Boolean.TRUE;
                if (!"0".equals(val)) return null;
                checked = true;
            }
            return checked ? Boolean.FALSE : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Lists activity tasks on the given display via IActivityTaskManager Binder IPC (~1 ms),
     * matching {@link com.deviceagent.tools.TaskSnapshot#parse} schema. Returns {@code null}
     * if reflection cannot inspect child tasks so callers can fall back to {@code am stack list}.
     */
    /** Displays that hold a task of this package, or null when Android does not say. */
    public static java.util.Set<Integer> taskDisplays(String packageName) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            if (atm == null) return null;
            Object listObj = atm.getClass().getMethod("getAllRootTaskInfos").invoke(atm);
            if (!(listObj instanceof java.util.List<?> list)) return null;
            java.util.Set<Integer> out = new java.util.TreeSet<>();
            for (Object info : list) {
                if (info == null) continue;
                Class<?> c = info.getClass();
                int display = ((Number) c.getField("displayId").get(info)).intValue();
                String[] names = (String[]) c.getField("childTaskNames").get(info);
                if (names == null) continue;
                for (String name : names) if (name != null && name.startsWith(packageName + "/")) out.add(display);
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static java.util.List<java.util.Map<String, Object>> tasksOnDisplay(int displayId) {
        try {
            Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            if (atm == null) return null;
            Object listObj = atm.getClass().getMethod("getAllRootTaskInfosOnDisplay", int.class).invoke(atm, displayId);
            if (!(listObj instanceof java.util.List<?> list)) return null;
            java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
            for (Object info : list) {
                if (info == null) continue;
                Class<?> c = info.getClass();
                int rootId = ((Number) c.getField("taskId").get(info)).intValue();
                boolean visible;
                try {
                    visible = Boolean.TRUE.equals(c.getField("visible").get(info));
                } catch (NoSuchFieldException ignored) {
                    visible = Boolean.TRUE.equals(c.getField("isVisible").get(info));
                }
                Object topObj = c.getField("topActivity").get(info);
                String topComponent = topObj instanceof android.content.ComponentName cn
                        ? cn.getPackageName() + "/" + cn.getClassName() : "";
                int[] childIds = (int[]) c.getField("childTaskIds").get(info);
                String[] childNames = (String[]) c.getField("childTaskNames").get(info);
                if (childIds == null || childNames == null || childIds.length != childNames.length) return null;
                int first = rows.size();
                for (int i = 0; i < childIds.length; i++) {
                    String component = childNames[i];
                    if (component == null) continue;
                    int slash = component.indexOf('/');
                    if (slash <= 0 || slash + 1 >= component.length()) continue;
                    rows.add(com.deviceagent.tools.Json.mapOf(
                            "activityTaskId", childIds[i],
                            "rootTaskId", rootId,
                            "displayId", displayId,
                            "component", component,
                            "package", component.substring(0, slash),
                            "activity", component.substring(slash + 1),
                            "visible", visible,
                            "rootVisible", visible,
                            "rootTopActivity", topComponent));
                }
                for (int i = first; i < rows.size(); i++) {
                    rows.get(i).put("rootTaskCount", rows.size() - first);
                }
            }
            return rows;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Current Wi-Fi connection, or {@link Wifi#NONE}. */
    public static Wifi wifi() {
        try {
            Object binder = service("wifi");
            if (binder == null) return Wifi.NONE;
            Object manager = asInterface("android.net.wifi.IWifiManager$Stub", binder);
            Object info = null;
            for (Method method : manager.getClass().getMethods()) {
                if (!method.getName().equals("getConnectionInfo")) continue;
                Class<?>[] types = method.getParameterTypes();
                Object[] args = new Object[types.length];
                for (int i = 0; i < types.length; i++) args[i] = types[i] == String.class && i == 0 ? SHELL_PACKAGE : null;
                info = method.invoke(manager, args);
                break;
            }
            if (info == null) return Wifi.NONE;
            Class<?> type = info.getClass();
            int networkId = (Integer) type.getMethod("getNetworkId").invoke(info);
            String bssid = (String) type.getMethod("getBSSID").invoke(info);
            String ssid = (String) type.getMethod("getSSID").invoke(info);
            if (networkId == -1 || !Wifi.validBssid(bssid)) return Wifi.NONE;
            return new Wifi(Wifi.unquote(ssid), bssid.toLowerCase(java.util.Locale.ROOT), networkId);
        } catch (Throwable t) {
            return Wifi.NONE;
        }
    }

    public static final class Wifi {
        public static final Wifi NONE = new Wifi(null, null, -1);
        public final String ssid;
        public final String bssid;
        public final int networkId;

        Wifi(String ssid, String bssid, int networkId) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.networkId = networkId;
        }

        public boolean connected() {
            return bssid != null;
        }

        static boolean validBssid(String bssid) {
            return bssid != null && bssid.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") && !"02:00:00:00:00:00".equals(bssid)
                    && !"00:00:00:00:00:00".equals(bssid);
        }

        static String unquote(String ssid) {
            if (ssid == null || "<unknown ssid>".equals(ssid)) return null;
            return ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
        }

        @Override
        public String toString() {
            return connected() ? "\"" + ssid + "\" " + bssid : "no Wi-Fi";
        }
    }
}
