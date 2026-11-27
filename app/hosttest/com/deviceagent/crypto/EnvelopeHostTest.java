package com.deviceagent.crypto;

import com.deviceagent.adb.Bytes;
import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.tools.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Envelope seal/open, tamper, replay; prints a cross-impl vector. */
public final class EnvelopeHostTest {
    public static void main(String[] args) throws Exception {
        run(System.out);
        System.out.println("EnvelopeHostTest OK");
    }

    public static Map<String, Object> run(PrintStream out) throws Exception {
        byte[] linkKey = new byte[32];
        Arrays.fill(linkKey, (byte) 0x22);
        byte[] callKey = LinkPairing.callKey(linkKey);
        byte[] resultKey = LinkPairing.resultKey(linkKey);
        check(callKey.length == 32, "callKey len");
        check(resultKey.length == 32, "resultKey len");
        check(!Arrays.equals(callKey, resultKey), "callKey == resultKey");

        String from = "agt_0123456789abcdef";
        long n = 1700000000000L;
        String plaintext = "{\"id\":\"t1\",\"name\":\"android.shell\",\"arguments\":{\"command\":\"id\"},\"timeoutMs\":12000}";
        byte[] pt = plaintext.getBytes(StandardCharsets.UTF_8);
        Envelope sealed = Envelope.seal(callKey, from, n, pt);
        check(sealed.v == 1, "v");
        check(from.equals(sealed.from), "from");
        check("1700000000000".equals(sealed.n), "n string");
        byte[] opened = Envelope.open(callKey, sealed);
        check(Arrays.equals(pt, opened), "round trip");

        Envelope parsed = Envelope.parse(sealed.toMap());
        check(Arrays.equals(pt, Envelope.open(callKey, parsed)), "parse+open");

        byte[] nonce = Envelope.nonce(n);
        check(nonce.length == 12, "nonce len");
        check(nonce[0] == 0 && nonce[1] == 0 && nonce[2] == 0 && nonce[3] == 0, "nonce prefix zeros");

        byte[] ct = LinkPairing.unb64url(sealed.ct);
        ct[0] ^= 0x01;
        Envelope tampered = new Envelope(sealed.v, sealed.from, sealed.n, LinkPairing.b64url(ct));
        boolean tamperFailed = false;
        try {
            Envelope.open(callKey, tampered);
        } catch (SecurityException e) {
            tamperFailed = true;
        }
        check(tamperFailed, "tamper should fail");

        Path tmp = Files.createTempDirectory("backstage-envelope-");
        DaemonConfig cfg = new DaemonConfig();
        cfg.configPath = tmp.resolve("daemon.json").toString();
        cfg.save();
        LinkStore store = new LinkStore(cfg);
        store.addLink(from, LinkPairing.b64url(linkKey));
        byte[] once = store.openCall(sealed);
        check(Arrays.equals(pt, once), "store open");
        boolean replayFailed = false;
        try {
            store.openCall(sealed);
        } catch (LinkStore.Unauthorized e) {
            replayFailed = "replay".equals(e.getMessage());
        }
        check(replayFailed, "replay should fail");

        Envelope other = Envelope.seal(callKey, "agt_unknown", n + 1, pt);
        boolean unknownFailed = false;
        try {
            store.openCall(other);
        } catch (LinkStore.Unauthorized e) {
            unknownFailed = "unknown agent".equals(e.getMessage());
        }
        check(unknownFailed, "unknown agent should fail");

        Envelope badV2N = new Envelope(2, from, "not-a-number", sealed.ct);
        badV2N.r = "AAAAAAAAAAAAAAAA";
        boolean badV2NRejected = false;
        try {
            store.openCall(badV2N);
        } catch (LinkStore.Unauthorized e) {
            badV2NRejected = true;
        }
        check(badV2NRejected, "malformed v2 n should fail with Unauthorized");

        Envelope badV2Ct = new Envelope(2, from, "1", "!!!invalid!!!");
        badV2Ct.r = "AAAAAAAAAAAAAAAA";
        boolean badV2CtRejected = false;
        try {
            store.openCall(badV2Ct);
        } catch (LinkStore.Unauthorized e) {
            badV2CtRejected = true;
        }
        check(badV2CtRejected, "malformed v2 ct should fail with Unauthorized");

        out.println("callKey=" + Bytes.toHex(callKey));
        out.println("resultKey=" + Bytes.toHex(resultKey));
        out.println("envelope=" + sealed.toJson());

        Map<String, Object> vec = new LinkedHashMap<>();
        vec.put("linkKeyHex", Bytes.toHex(linkKey));
        vec.put("callKeyHex", Bytes.toHex(callKey));
        vec.put("resultKeyHex", Bytes.toHex(resultKey));
        vec.put("from", from);
        vec.put("n", sealed.n);
        vec.put("plaintext", plaintext);
        vec.put("envelope", sealed.toMap());
        return vec;
    }

    private static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
