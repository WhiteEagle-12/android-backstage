package com.deviceagent.shell;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Parsing and policy for keeping Wireless debugging approved on networks the owner
 * explicitly trusted.
 *
 * Android remembers "Always allow on this network" per access point (BSSID), not per
 * network name, so a multi-AP home mesh asks again on another AP of the same SSID.
 * Backstage only auto-approves the currently connected, WPA-authenticated BSSID when
 * the owner has already approved that SSID at least once. Open/OWE networks and
 * arbitrary saved Wi-Fi networks (hotels, offices, cafes) are never auto-approved.
 */
public final class WifiTrustRules {
    private WifiTrustRules() {}

    public static final int MIN_RSSI = -90;
    /** Access points do not move; a sighting in the last hour (5 and 6 GHz scans are infrequent) is good enough. */
    public static final double MAX_SCAN_AGE_SECONDS = 3600;
    private static final Pattern TRUSTED = Pattern.compile("<wifiAP\\s+bssid=\"([0-9A-Fa-f:]{17})\"");
    private static final Pattern NETWORK = Pattern.compile("^\\s*(\\d+)\\s+(.+?)\\s{2,}(\\S.*?)\\s*$");
    private static final Pattern SCAN = Pattern.compile(
            "^\\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})\\s+(\\d+)\\s+(-?\\d+)\\S*\\s+([0-9.]+)\\s+(.*?)\\s*(\\[.*)?$");

    public static final class Scan {
        public final String bssid;
        public final String ssid;
        public final int rssi;
        public final double ageSeconds;

        public Scan(String bssid, String ssid, int rssi, double ageSeconds) {
            this.bssid = bssid;
            this.ssid = ssid;
            this.rssi = rssi;
            this.ageSeconds = ageSeconds;
        }
    }

    /** Access points Android already trusts, from {@code dumpsys adb}. */
    public static Set<String> trustedBssids(String dumpsysAdb) {
        Set<String> out = new LinkedHashSet<>();
        if (dumpsysAdb == null) return out;
        Matcher m = TRUSTED.matcher(dumpsysAdb);
        while (m.find()) out.add(m.group(1).toLowerCase(Locale.ROOT));
        return out;
    }

    /**
     * Saved networks from {@code cmd wifi list-networks}: SSID → true when at least one
     * saved security type needs a password or enterprise sign-in.
     */
    public static Map<String, Boolean> savedNetworks(String listNetworks) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (listNetworks == null) return out;
        for (String line : listNetworks.split("\n")) {
            Matcher m = NETWORK.matcher(line);
            if (!m.matches()) continue;
            String ssid = m.group(2).trim();
            String security = m.group(3).trim().toLowerCase(Locale.ROOT).replace("^", "");
            boolean secured = !(security.startsWith("open") || security.startsWith("owe") || security.isEmpty());
            out.merge(ssid, secured, (a, b) -> a || b);
        }
        return out;
    }

    /** Cached scan results from {@code cmd wifi list-scan-results}. Hidden networks have an empty SSID. */
    public static List<Scan> scanResults(String text) {
        List<Scan> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            Matcher m = SCAN.matcher(line);
            if (!m.matches()) continue;
            try {
                out.add(new Scan(m.group(1).toLowerCase(Locale.ROOT), m.group(5).trim(),
                        Integer.parseInt(m.group(3)), Double.parseDouble(m.group(4))));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Only explicitly owner-approved SSIDs ({@code learned}) are eligible for automatic
     * BSSID approval, and open/OWE networks are always excluded.
     */
    public static boolean eligible(String ssid, Map<String, Boolean> saved, Set<String> learned) {
        if (ssid == null || ssid.isEmpty() || learned == null || !learned.contains(ssid)) return false;
        if (saved != null && Boolean.FALSE.equals(saved.get(ssid))) return false;
        return true;
    }

    /** HMAC-SHA256 tag over the canonical sorted BSSID set so external-storage tampering is rejected. */
    public static String signTrustedBssids(Set<String> bssids, String localToken) {
        if (localToken == null || localToken.isBlank()) return "";
        List<String> sorted = new ArrayList<>();
        if (bssids != null) {
            for (String b : bssids) {
                if (b != null && !b.isBlank()) sorted.add(b.trim().toLowerCase(Locale.ROOT));
            }
        }
        Collections.sort(sorted);
        String payload = "wifi-trust-v1\n" + String.join("\n", sorted);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(localToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verifyTrustedBssids(Set<String> bssids, String localToken, String tag) {
        if (tag == null || tag.isBlank() || localToken == null || localToken.isBlank()) return false;
        String expected = signTrustedBssids(bssids, localToken);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                tag.trim().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Nearby access points of eligible networks that Android does not trust yet,
     * strongest first, excluding the current one (approving that also turns
     * Wireless debugging on, so it goes last).
     */
    public static List<String> neighborsToTrust(List<Scan> scans, Map<String, Boolean> saved, Set<String> learned,
            Set<String> trusted, String currentBssid, int limit) {
        List<Scan> picked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Scan scan : scans) {
            if (!eligible(scan.ssid, saved, learned)) continue;
            if (scan.rssi < MIN_RSSI || scan.ageSeconds > MAX_SCAN_AGE_SECONDS) continue;
            if (trusted.contains(scan.bssid) || scan.bssid.equalsIgnoreCase(currentBssid == null ? "" : currentBssid)) continue;
            if (seen.add(scan.bssid)) picked.add(scan);
        }
        picked.sort((a, b) -> Integer.compare(b.rssi, a.rssi));
        List<String> out = new ArrayList<>();
        for (Scan scan : picked) {
            if (out.size() >= limit) break;
            out.add(scan.bssid);
        }
        return out;
    }
}
