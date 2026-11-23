package com.deviceagent.crypto;

import com.deviceagent.adb.Bytes;
import com.deviceagent.adb.Spake2;
import com.deviceagent.tools.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPAKE2 Bob vector from {@code docs/spake2-backstage-vector.json} plus confirmA/confirmB.
 */
public final class LinkPairingHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out, args.length > 0 ? Path.of(args[0]) : findVector());
        System.out.println("LinkPairingHostTest OK");
    }

    public static Map<String, Object> run(PrintStream out, Path vectorPath) throws Exception {
        if (vectorPath == null || !Files.isRegularFile(vectorPath)) {
            throw new AssertionError("missing SPAKE2 vector file: " + vectorPath);
        }
        Map<String, Object> vec = Json.object(Files.readString(vectorPath));
        String password = Json.string(vec, "password");
        String normalized = LinkPairing.normalizeCode(password);
        check(normalized.equals(password), "normalize left ABCD-EFGHJK unchanged");
        check(LinkPairing.normalizeCode("  abcd efghjk ").equals(password), "normalize insert dash");
        check("ABCD".equals(LinkPairing.sessionId(password)), "session id");

        byte[] aliceName = stringBytes(vec.get("aliceName"));
        byte[] bobName = stringBytes(vec.get("bobName"));
        check(Arrays.equals(LinkPairing.AGENT_NAME, aliceName), "aliceName");
        check(Arrays.equals(LinkPairing.DEVICE_NAME, bobName), "bobName");
        check(LinkPairing.AGENT_NAME.length == 16, "agent name 16 bytes incl NUL");
        check(LinkPairing.DEVICE_NAME[LinkPairing.DEVICE_NAME.length - 1] == 0, "device name trailing NUL");

        byte[] bobRandom = Bytes.fromHex(Json.string(vec, "bobRandom64"));
        byte[] aliceMsg = Bytes.fromHex(Json.string(vec, "aliceMsg"));
        byte[] expectBobMsg = Bytes.fromHex(Json.string(vec, "bobMsg"));
        byte[] expectKey = Bytes.fromHex(Json.string(vec, "key"));

        Spake2 bob = LinkPairing.bob();
        byte[] bobMsg = bob.generateMsg(password.getBytes(StandardCharsets.US_ASCII), bobRandom);
        check(Bytes.toHex(bobMsg).equals(Bytes.toHex(expectBobMsg)),
                "bobMsg got " + Bytes.toHex(bobMsg) + " want " + Bytes.toHex(expectBobMsg));
        byte[] key = bob.processMsg(aliceMsg);
        check(Bytes.toHex(key).equals(Bytes.toHex(expectKey)),
                "key got " + Bytes.toHex(key) + " want " + Bytes.toHex(expectKey));

        byte[] linkKey = LinkPairing.linkKey(key);
        String confirmA = LinkPairing.confirmAB64(linkKey);
        String confirmB = LinkPairing.confirmBB64(linkKey);
        out.println("linkKey=" + Bytes.toHex(linkKey));
        out.println("confirmA=" + confirmA);
        out.println("confirmB=" + confirmB);

        Map<String, Object> printed = new LinkedHashMap<>();
        printed.put("password", password);
        printed.put("bobMsgHex", Bytes.toHex(bobMsg));
        printed.put("keyHex", Bytes.toHex(key));
        printed.put("linkKeyHex", Bytes.toHex(linkKey));
        printed.put("confirmA", confirmA);
        printed.put("confirmB", confirmB);
        printed.put("confirmAHex", Bytes.toHex(LinkPairing.confirmA(linkKey)));
        printed.put("confirmBHex", Bytes.toHex(LinkPairing.confirmB(linkKey)));
        return printed;
    }

    public static Path findVector() {
        String prop = System.getProperty("backstage.vector");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        String[] candidates = {
                "docs/spake2-backstage-vector.json",
                "../docs/spake2-backstage-vector.json",
                "/workspace/docs/spake2-backstage-vector.json"
        };
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p)) return p;
        }
        return Path.of("docs/spake2-backstage-vector.json");
    }

    private static byte[] stringBytes(Object raw) {
        if (raw == null) return new byte[0];
        return String.valueOf(raw).getBytes(StandardCharsets.UTF_8);
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
