package com.deviceagent.adb;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host self-test 4: AdbMessage encode/decode including checksum. */
public final class AdbMessageHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("AdbMessageHostTest OK");
    }

    static void run(PrintStream out) throws Exception {
        byte[] payload = "host::features=shell_v2,cmd\0".getBytes(StandardCharsets.UTF_8);
        AdbMessage m = new AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, payload);
        check(m.magic == (AdbProtocol.A_CNXN ^ 0xffffffff), "magic");
        check(m.dataCheck == AdbMessage.checksum(payload), "checksum field");
        byte[] encoded = m.encode();
        check(encoded.length == 24 + payload.length, "wire length");
        AdbMessage round = AdbMessage.decode(encoded);
        check(round.equals(m), "round-trip equals");
        check(round.command == AdbProtocol.A_CNXN, "cmd");
        check(Arrays.equals(round.data, payload), "payload");

        AdbMessage empty = new AdbMessage(AdbProtocol.A_OKAY, 1, 2, (byte[]) null);
        AdbMessage empty2 = AdbMessage.decode(empty.encode());
        check(empty2.arg0 == 1 && empty2.arg1 == 2 && empty2.data.length == 0, "empty OKAY");
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
