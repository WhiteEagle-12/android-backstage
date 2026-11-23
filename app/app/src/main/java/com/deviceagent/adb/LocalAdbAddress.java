package com.deviceagent.adb;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/** A discovered port is used on loopback, so its advertiser must be this device. */
final class LocalAdbAddress {
    private LocalAdbAddress() {}

    static boolean isLocal(InetAddress address) {
        if (address == null || address.isAnyLocalAddress()) return false;
        if (address.isLoopbackAddress()) return true;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            for (NetworkInterface iface : Collections.list(interfaces)) {
                for (InetAddress local : Collections.list(iface.getInetAddresses())) {
                    if (address.equals(local)) return true;
                }
            }
        } catch (Exception ignored) {
            // Failure to identify the advertiser must not select another phone's port.
        }
        return false;
    }
}
