package com.deviceagent.rpc;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Non-loopback IPv4 addresses, for hello and the LAN-direct card. */
public final class LanAddresses {
    private LanAddresses() {}

    public static List<String> ipv4() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return out;
            for (NetworkInterface ni : Collections.list(ifs)) {
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isAnyLocalAddress()) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.isBlank() && !out.contains(host)) out.add(host);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
