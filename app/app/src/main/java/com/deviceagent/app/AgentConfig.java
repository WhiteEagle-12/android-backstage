package com.deviceagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.tools.Json;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class AgentConfig {
    private static final String PREFS = "backstage";

    public String relayUrl = "";
    public String deviceToken = "";
    public String deviceName = "";
    public String deviceId = "";
    public String localToken = "";
    public boolean paired;
    public boolean accessEnabled;
    public boolean bootBlocked;
    public boolean oemAutostartAcked;
    public boolean keepUsbDebugging;
    public boolean allowShell;
    public boolean allowScreenOff;
    public boolean optionalTourDone;
    public int lastConnectPort;
    public int lastPairPort;
    /** Daemon inbound port (LAN envelopes + supervision). Default 18422. */
    public int daemonPort = 18422;
    public List<DaemonConfig.Link> links = new ArrayList<>();

    public static AgentConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, 0);
        AgentConfig c = new AgentConfig();
        c.relayUrl = prefs.getString("relay_url", "");
        c.deviceToken = prefs.getString("device_token", "");
        c.deviceName = prefs.getString("device_name", "");
        c.deviceId = prefs.getString("device_id", "");
        c.localToken = prefs.getString("local_token", "");
        c.paired = prefs.getBoolean("paired", false);
        c.accessEnabled = prefs.getBoolean("access_enabled", false);
        c.bootBlocked = prefs.getBoolean("boot_blocked", false);
        c.oemAutostartAcked = prefs.getBoolean("oem_autostart_acked", false);
        c.keepUsbDebugging = prefs.getBoolean("keep_usb_debugging", false);
        c.allowShell = prefs.getBoolean("allow_shell", false);
        c.allowScreenOff = prefs.getBoolean("allow_screen_off", false);
        c.optionalTourDone = prefs.getBoolean("optional_tour_done", false);
        c.lastConnectPort = prefs.getInt("last_connect_port", 0);
        c.lastPairPort = prefs.getInt("last_pair_port", 0);
        c.daemonPort = prefs.getInt("daemon_port", 18422);
        if (c.daemonPort <= 0 || c.daemonPort >= 65536) c.daemonPort = 18422;
        try {
            Object parsed = Json.parse(prefs.getString("links_json", "[]"));
            c.links = DaemonConfig.parseLinks(parsed);
        } catch (RuntimeException e) {
            c.links = new ArrayList<>();
        }
        if (c.localToken == null || c.localToken.isBlank() || prefs.getInt("local_token_version", 0) < 2) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            c.localToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            c.save(context);
        }
        return c;
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, 0).edit()
                .putString("relay_url", relayUrl == null ? "" : relayUrl)
                .putString("device_token", deviceToken == null ? "" : deviceToken)
                .putString("device_name", deviceName == null ? "" : deviceName)
                .putString("device_id", deviceId == null ? "" : deviceId)
                .putInt("local_token_version", 2)
                .putString("local_token", localToken == null ? "" : localToken)
                .putString("links_json", linksJson())
                .putInt("daemon_port", daemonPort)
                .apply();
        StartScripts.cleanup(context);
    }

    /** Discovery must not overwrite relay links or newer ports from a stale snapshot. */
    public static void saveAdbState(Context context, Integer connectPort, Integer pairPort, Boolean paired) {
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, 0).edit();
        if (connectPort != null) edit.putInt("last_connect_port", connectPort);
        if (pairPort != null) edit.putInt("last_pair_port", pairPort);
        if (paired != null) edit.putBoolean("paired", paired);
        edit.apply();
    }

    /** Kept separate so a stale settings snapshot cannot undo Pause. */
    public static void setAccessEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, 0).edit()
                .putBoolean("access_enabled", enabled).apply();
        if(!enabled)ScreenControlProvider.clear(context);
    }
    public static void setBootBlocked(Context context, boolean blocked) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("boot_blocked", blocked).apply();
    }
    public static void setOemAutostartAcked(Context context, boolean acked) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("oem_autostart_acked", acked).apply();
    }
    public static void setKeepUsbDebugging(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("keep_usb_debugging", enabled).commit();
    }
    public static void setAllowShell(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("allow_shell", enabled).commit();
    }
    public static void setAllowScreenOff(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("allow_screen_off", enabled).commit();
    }
    public static void setOptionalTourDone(Context context, boolean done) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("optional_tour_done", done).commit();
    }
    public static void savePort(Context context, int port) {
        context.getSharedPreferences(PREFS,0).edit().putInt("daemon_port",port).commit();
    }

    public boolean hasAssistant() {
        return hasRelay() && links != null && !links.isEmpty();
    }

    public boolean hasRelay() {
        return relayUrl != null && !relayUrl.isBlank() && deviceToken != null && !deviceToken.isBlank();
    }

    public void upsertLink(String agentId, String linkKey) {
        if (agentId == null || agentId.isBlank() || linkKey == null || linkKey.isBlank()) return;
        forgetLink(agentId);
        DaemonConfig.Link link = new DaemonConfig.Link();
        link.agentId = agentId.trim();
        link.linkKey = linkKey.trim();
        link.lastCallCounter = "0";
        link.resultCounter = Long.toUnsignedString(System.currentTimeMillis());
        links.add(link);
    }

    public void forgetLink(String agentId) {
        if (agentId == null || agentId.isBlank()) return;
        String id = agentId.trim();
        links.removeIf(l -> l != null && id.equals(l.agentId));
    }

    public String linksJson() {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (links != null) {
            for (DaemonConfig.Link link : links) {
                if (link == null) continue;
                rows.add(Json.mapOf(
                        "agentId", link.agentId == null ? "" : link.agentId,
                        "linkKey", link.linkKey == null ? "" : link.linkKey,
                        "lastCallCounter", link.lastCallCounter == null ? "0" : link.lastCallCounter,
                        "resultCounter", link.resultCounter == null ? "0" : link.resultCounter));
            }
        }
        return Json.stringify(rows);
    }

    /** Labels belong to the owner, not a claimed model or individual conversation. */
    public static String connectionName(Context context, String id) {
        String saved = context.getSharedPreferences(PREFS, 0).getString("client_name_" + id, "");
        return saved == null || saved.isBlank() ? "Unnamed connection · " + id.substring(Math.max(0, id.length()-6)) : saved;
    }

    public static void renameConnection(Context context, String id, String name) {
        String clean = SetupRules.connectionName(name);
        if (AgentConfig.load(context).links.stream().noneMatch(link -> id.equals(link.agentId)))
            throw new IllegalArgumentException("This connection no longer exists.");
        if (!context.getSharedPreferences(PREFS, 0).edit().putString("client_name_" + id, clean).commit())
            throw new IllegalStateException("The name could not be saved.");
    }

    static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value.trim());
            return (port <= 0 || port >= 65536) ? fallback : port;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
