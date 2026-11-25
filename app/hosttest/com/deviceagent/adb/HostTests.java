package com.deviceagent.adb;

import com.deviceagent.crypto.EnvelopeHostTest;
import com.deviceagent.crypto.FileCipherHostTest;
import com.deviceagent.crypto.LinkPairingHostTest;
import com.deviceagent.tools.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runs every host self-test. */
public final class HostTests {
    public static void main(String[] args) throws Exception {
        com.deviceagent.tools.ControlsHostTest.run();
        com.deviceagent.tools.DeviceCheckGuardHostTest.run();
        com.deviceagent.tools.FileChunksHostTest.run();
        com.deviceagent.tools.InputGuardHostTest.run();
        com.deviceagent.tools.EvidenceHostTest.run();
        com.deviceagent.tools.ObservationHostTest.run();
        com.deviceagent.tools.SemanticHostTest.run();
        com.deviceagent.tools.UsabilityHostTest.run();
        com.deviceagent.tools.ReliabilityHostTest.run();
        com.deviceagent.tools.PhoneToolsHostTest.run();
        com.deviceagent.app.SetupHostTest.run();
        com.deviceagent.shell.WifiTrustHostTest.run();
        com.deviceagent.crypto.HardeningHostTest.run();
        com.deviceagent.crypto.RequestJournalHostTest.run();
        com.deviceagent.daemon.StartupHostTest.run();
        com.deviceagent.rpc.ReceivedJobsHostTest.run();
        System.out.println("PASS Startup: failed retries retain counters and revocations");
        LocalAdbAddressHostTest.run();
        System.out.println("PASS LocalAdbAddress");
        AdbKeyHostTest.run(System.out);
        System.out.println("PASS AdbKey");
        Spake2HostTest.run(System.out);
        System.out.println("PASS Spake2");
        AesGcmHostTest.run(System.out);
        System.out.println("PASS Aes128Gcm");
        AdbMessageHostTest.run(System.out);
        System.out.println("PASS AdbMessage");
        FakeAdbdHostTest.run(System.out);
        System.out.println("PASS FakeAdbd");
        FakePairingHostTest.run(System.out);
        System.out.println("PASS FakePairing");

        Map<String, Object> envelope = EnvelopeHostTest.run(System.out);
        System.out.println("PASS Envelope");
        Map<String, Object> file = FileCipherHostTest.run(System.out);
        System.out.println("PASS FileCipher");
        Path vector = args.length > 0 ? Path.of(args[0]) : LinkPairingHostTest.findVector();
        Map<String, Object> pairing = LinkPairingHostTest.run(System.out, vector);
        System.out.println("PASS LinkPairing");

        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("envelope", envelope);
        vectors.put("file", file);
        vectors.put("spake2", pairing);
        String json = Json.stringify(vectors) + "\n";
        Path out = args.length > 1
                ? Path.of(args[1])
                : Path.of(System.getProperty("backstage.vectors.out", "e2e-vectors-java.json"));
        Path parent = out.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(out, json.getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + out.toAbsolutePath());
        System.out.println("ALL HOST TESTS PASSED");
    }
}
