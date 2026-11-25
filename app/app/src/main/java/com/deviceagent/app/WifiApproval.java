package com.deviceagent.app;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import com.deviceagent.tools.Json;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether turning Wireless debugging on here would make Android ask the owner.
 * Android trusts individual access points (BSSIDs). The shell daemon publishes the
 * trusted list to wifi-trust.json and approves each access point of the owner's
 * networks as the phone reaches it; the app only turns Wireless debugging on by
 * itself where Android already trusts the current access point.
 */
final class WifiApproval {
    private WifiApproval() {}

    /** Null when Wireless debugging can turn on silently; otherwise why not, for the owner. */
    static String blockedReason(Context context) {
        String bssid = currentBssid(context);
        // Without background location, Android hides the network's identity from a service started at boot.
        if (bssid == null) return onWifi(context)
                ? "Android hides which Wi-Fi this is until you open Backstage."
                : "Backstage reconnects automatically on an approved Wi-Fi network.";
        if (trusted(context).contains(bssid)) return null;
        return "This Wi-Fi network isn’t approved for automatic reconnect yet.";
    }

    private static boolean onWifi(Context context) {
        try {
            var cm = context.getSystemService(android.net.ConnectivityManager.class);
            var caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    static String currentBssid(Context context) {
        try {
            WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1) return null;
            String bssid = info.getBSSID();
            if (bssid == null || !bssid.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") || "02:00:00:00:00:00".equals(bssid)) return null;
            return bssid.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Set<String> trusted(Context context) {
        Set<String> out = new HashSet<>();
        try {
            File dir = context.getExternalFilesDir(null);
            File file = dir == null ? null : new File(dir, "wifi-trust.json");
            if (file == null || !file.isFile()) return out;
            java.util.Map<String, Object> obj = Json.object(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            Object list = obj.get("trustedBssids");
            if (list instanceof List<?> rows) {
                for (Object row : rows) {
                    if (row instanceof String b && !b.isBlank()) out.add(b.toLowerCase(Locale.ROOT));
                }
            }
            String token = AgentConfig.load(context).localToken;
            String mac = obj.get("mac") instanceof String s ? s : "";
            if (!com.deviceagent.shell.WifiTrustRules.verifyTrustedBssids(out, token, mac)) {
                return new HashSet<>();
            }
        } catch (Exception ignored) {
            return new HashSet<>();
        }
        return out;
    }
}
