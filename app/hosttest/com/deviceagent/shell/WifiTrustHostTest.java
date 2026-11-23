package com.deviceagent.shell;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Multi-AP home and campus Wi-Fi dumpsys/scan fixtures. */
public final class WifiTrustHostTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static final String DUMPSYS = "    keystore=<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
            + "    <keyStore version=\"1\">\n"
            + "    <adbKey key=\"QAAAA deviceagent@local\" lastConnection=\"1791864854739\" />\n"
            + "    <wifiAP bssid=\"02:00:5e:00:00:0e\" />\n"
            + "    <wifiAP bssid=\"02:00:5E:20:0B:90\" />\n"
            + "    <wifiAP bssid=\"02:00:5e:10:73:91\" />\n"
            + "    </keyStore>\n";
    static final String NETWORKS = "Network Id      SSID                         Security type\n"
            + "0            HomeMesh                         wpa2-psk\n"
            + "0            HomeMesh                         wpa3-sae^\n"
            + "1            HomeMesh_6e                      wpa3-sae\n"
            + "3            CampusNet                        wpa2-enterprise\n"
            + "3            CampusNet                        wpa3 enterprise^\n"
            + "9            CoffeeShop Public                open\n"
            + "9            CoffeeShop Public                owe^\n"
            + "5            GuestNet (pass: demo1234)        wpa2-psk\n";
    static final String SCAN = "    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags\n"
            + "  02:00:5e:10:73:a1       2437    -65(0:-73/1:-66)   143.688    HomeMesh_legacy                   [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]\n"
            + "  02:00:5e:30:38:2f       5745    -74(0:-76/1:-79)   144.448                                      [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]\n"
            + "  02:00:5e:10:62:91       5540    -83(0:-89/1:-84)   847.618    HomeMesh                          [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]\n"
            + "  02:00:5e:10:73:91       5765    -65(0:-67/1:-70)   142.558    HomeMesh                          [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]\n"
            + "  02:00:5e:10:7d:51       5660    -70(0:-90/1:-94)    46.244    HomeMesh                          [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]\n"
            + "  02:00:5e:10:99:91       5660    -60                4000.000    HomeMesh                          [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]\n"
            + "  02:00:5e:10:6f:41       6135    -60                 12.000    HomeMesh_6e                       [RSN-SAE-CCMP-128][ESS]\n"
            + "  02:00:5e:10:7e:41       6135    -95                 12.000    HomeMesh_6e                       [RSN-SAE-CCMP-128][ESS]\n"
            + "  02:00:5e:20:0b:94       5180    -55                  3.100    CampusNet                         [WPA2-EAP-CCMP][ESS]\n"
            + "  02:00:5e:20:0b:90       5180    -57                  3.100    CampusNet                         [WPA2-EAP-CCMP][ESS]\n"
            + "  02:00:5e:40:21:e3       5765    -50(0:-88/1:-88)   144.374    CoffeeShop Public                 [ESS]\n"
            + "  02:00:5e:50:ac:ed       2422    -40(0:-91/1:-89)   508.282    ROUTER94                          [WPA2-PSK-CCMP-128][ESS][WPS]\n";

    public static void run() {
        Set<String> trusted = WifiTrustRules.trustedBssids(DUMPSYS);
        check(trusted.equals(Set.of("02:00:5e:00:00:0e", "02:00:5e:20:0b:90", "02:00:5e:10:73:91")), "trusted BSSIDs, lower-cased: " + trusted);
        check(WifiTrustRules.trustedBssids("no keystore").isEmpty(), "no trust list");

        Map<String, Boolean> saved = WifiTrustRules.savedNetworks(NETWORKS);
        check(Boolean.TRUE.equals(saved.get("HomeMesh")), "PSK network eligible");
        check(Boolean.TRUE.equals(saved.get("HomeMesh_6e")), "SAE network eligible");
        check(Boolean.TRUE.equals(saved.get("CampusNet")), "enterprise network eligible");
        check(Boolean.FALSE.equals(saved.get("CoffeeShop Public")), "open/OWE network never eligible");
        check(Boolean.TRUE.equals(saved.get("GuestNet (pass: demo1234)")), "SSID with punctuation parsed");
        check(!saved.containsKey("Network Id"), "header skipped");

        List<WifiTrustRules.Scan> scans = WifiTrustRules.scanResults(SCAN);
        check(scans.size() == 12, "every scan row parsed, including hidden SSIDs: " + scans.size());
        check(scans.get(1).ssid.isEmpty(), "hidden SSID is empty");
        check(scans.get(6).rssi == -60 && scans.get(6).ssid.equals("HomeMesh_6e"), "RSSI without per-chain detail");

        check(!WifiTrustRules.eligible("HomeMesh", saved, Set.of()), "saved secured requires explicit owner approval");
        check(WifiTrustRules.eligible("HomeMesh", saved, Set.of("HomeMesh")), "owner-approved secured network eligible");
        check(!WifiTrustRules.eligible("CoffeeShop Public", saved, Set.of()), "open excluded");
        check(!WifiTrustRules.eligible("CoffeeShop Public", saved, Set.of("CoffeeShop Public")), "open excluded even if learned");
        check(!WifiTrustRules.eligible("HomeMesh_legacy", saved, Set.of()), "unsaved excluded");
        check(WifiTrustRules.eligible("HomeMesh_legacy", saved, Set.of("HomeMesh_legacy")), "approved by the owner once");
        check(!WifiTrustRules.eligible(null, saved, Set.of()) && !WifiTrustRules.eligible("", saved, Set.of()), "no SSID");

        Set<String> learned = Set.of("HomeMesh", "HomeMesh_6e", "CampusNet");
        List<String> pick = WifiTrustRules.neighborsToTrust(scans, saved, learned, trusted, "02:00:5e:10:6f:41", 40);
        // 02:00:5e:10:6f:41 is current, 7e:41 too weak, 99:91 not seen for over an hour,
        // 0b:90 already trusted, open/unsaved/hidden/unapproved never; strongest first.
        check(pick.equals(List.of("02:00:5e:20:0b:94", "02:00:5e:10:7d:51", "02:00:5e:10:62:91")), "neighbors to approve: " + pick);
        check(WifiTrustRules.neighborsToTrust(scans, saved, Set.of(), trusted, "02:00:5e:10:6f:41", 40).isEmpty(), "no neighbors without explicit approval");
        check(WifiTrustRules.neighborsToTrust(scans, saved, learned, trusted, null, 1).size() == 1, "limit respected");

        String token = "test-local-token-32-bytes";
        String mac = WifiTrustRules.signTrustedBssids(trusted, token);
        check(!mac.isEmpty(), "HMAC generated");
        check(WifiTrustRules.verifyTrustedBssids(Set.of("02:00:5E:10:73:91", "02:00:5e:00:00:0e", "02:00:5e:20:0b:90"), token, mac), "HMAC order & case insensitive");
        check(!WifiTrustRules.verifyTrustedBssids(Set.of("02:00:5e:00:00:0e", "aa:bb:cc:dd:ee:ff"), token, mac), "tampered BSSID rejected");
        check(!WifiTrustRules.verifyTrustedBssids(trusted, "wrong-token", mac), "wrong token rejected");
        check(!WifiTrustRules.verifyTrustedBssids(trusted, token, ""), "missing MAC rejected");
        System.out.println("PASS WifiTrust: owner-approved Wi-Fi policy + HMAC verification");
    }
}
