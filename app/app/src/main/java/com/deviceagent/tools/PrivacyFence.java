package com.deviceagent.tools;

import com.deviceagent.daemon.DaemonConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * On-device privacy fence enforced by the daemon itself. Blocked packages are
 * refused before open, observe, screenshot, input, notification, audio, or
 * developer actions can touch them.
 */
public final class PrivacyFence {
    private static volatile PrivacyFence active;
    private final DaemonConfig config;
    private final String selfPackage;
    private final Set<String> blocked = new CopyOnWriteArraySet<>();

    public PrivacyFence(DaemonConfig config) {
        this.config = config;
        String pkg = config != null && config.packageName != null && !config.packageName.isBlank()
                ? config.packageName.trim() : com.deviceagent.app.BuildInfo.DEFAULT_PACKAGE;
        this.selfPackage = normalize(pkg);
        if (config != null && config.blockedPackages != null) {
            for (String p : config.blockedPackages) {
                String clean = normalize(p);
                if (!clean.isEmpty() && !clean.equals(this.selfPackage)) blocked.add(clean);
            }
        }
        active = this;
    }

    public static PrivacyFence active() {
        return active;
    }

    public static boolean isGloballyBlocked(String pkg) {
        PrivacyFence fence = active;
        return fence != null && fence.isBlocked(pkg);
    }

    public boolean isEnforced() {
        return !selfPackage.isEmpty() || !blocked.isEmpty();
    }

    public boolean isBlocked(String pkg) {
        String clean = normalize(pkg);
        if (clean.isEmpty()) return false;
        return (!selfPackage.isEmpty() && selfPackage.equals(clean)) || blocked.contains(clean);
    }

    public List<String> allBlocked() {
        Set<String> all = new TreeSet<>(blocked);
        if (!selfPackage.isEmpty()) all.add(selfPackage);
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    public String packageOfComponent(String component) {
        if (component == null) return "";
        String s = component.trim();
        int slash = s.indexOf('/');
        return normalize(slash > 0 ? s.substring(0, slash) : s);
    }

    public void requireAllowed(String pkg) {
        if (isBlocked(pkg)) {
            throw new InputNotSentException("blocked_app", "This app is blocked in Backstage privacy settings.");
        }
    }

    public void requireComponentAllowed(String component) {
        String pkg = packageOfComponent(component);
        if (!pkg.isEmpty()) requireAllowed(pkg);
    }

    public List<String> list() {
        List<String> out = new ArrayList<>(new TreeSet<>(blocked));
        return Collections.unmodifiableList(out);
    }

    public synchronized List<String> set(List<String> packages) throws IOException {
        Set<String> next = new TreeSet<>();
        if (packages != null) {
            for (String pkg : packages) {
                String clean = normalize(pkg);
                if (!clean.isEmpty() && clean.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                    next.add(clean);
                }
            }
        }
        blocked.clear();
        blocked.addAll(next);
        if (config != null) {
            config.blockedPackages = new ArrayList<>(next);
            config.save();
        }
        return list();
    }

    public int count() {
        return blocked.size();
    }

    private static String normalize(String pkg) {
        return pkg == null ? "" : pkg.trim();
    }
}
