package com.deviceagent.adb;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

final class LocalAdbAddressHostTest {
    static void run() throws Exception {
        check(LocalAdbAddress.isLocal(InetAddress.getByName("127.0.0.1")), "IPv4 loopback");
        check(LocalAdbAddress.isLocal(InetAddress.getByName("::1")), "IPv6 loopback");
        check(!LocalAdbAddress.isLocal(InetAddress.getByName("0.0.0.0")), "unspecified advertiser");
        check(!LocalAdbAddress.isLocal(InetAddress.getByName("192.0.2.123")), "another phone's IPv4 port");
        check(!LocalAdbAddress.isLocal(InetAddress.getByName("2001:db8::123")), "another phone's IPv6 port");
        check(!LocalAdbAddress.isLocal(null), "missing advertiser");
        for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                check(LocalAdbAddress.isLocal(address), "this device's interface " + address);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
