package com.deviceagent.adb;

/** ADB protocol constants from AOSP {@code adb.h} / {@code protocol.txt}. */
final class AdbProtocol {
    private AdbProtocol() {}

    static final int A_SYNC = 0x434e5953;
    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_STLS = 0x534c5453;

    /** Current ADB protocol version (skip checksum). */
    static final int A_VERSION = 0x01000001;
    static final int A_VERSION_SKIP_CHECKSUM = 0x01000001;
    static final int A_STLS_VERSION = 0x01000000;
    static final int A_MAXDATA = 1024 * 1024;

    static final String CONNECT_BANNER = "host::features=shell_v2,cmd";

    /** Pairing packet types from AOSP {@code proto/pairing.proto} (proto3 enum). */
    static final byte PAIRING_VERSION = 1;
    static final byte PAIRING_SPAKE2_MSG = 0;
    static final byte PAIRING_PEER_INFO = 1;
    static final int PAIRING_HEADER_SIZE = 6;
    static final int PEER_INFO_SIZE = 8192;
    static final byte PEER_INFO_ADB_RSA_PUB_KEY = 0;

    /** TLS exporter label including the trailing NUL ({@code sizeof("adb-label")}). */
    static final String EXPORTED_KEY_LABEL = "adb-label\0";
    static final int EXPORTED_KEY_SIZE = 64;

    static String cmdName(int cmd) {
        switch (cmd) {
            case A_SYNC:
                return "A_SYNC";
            case A_CNXN:
                return "A_CNXN";
            case A_AUTH:
                return "A_AUTH";
            case A_OPEN:
                return "A_OPEN";
            case A_OKAY:
                return "A_OKAY";
            case A_CLSE:
                return "A_CLSE";
            case A_WRTE:
                return "A_WRTE";
            case A_STLS:
                return "A_STLS";
            default:
                return String.format("0x%08x", cmd);
        }
    }
}
