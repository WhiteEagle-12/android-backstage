package com.deviceagent.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class StartupHostTest {
    public static void run() throws Exception {
        Path dir = Files.createTempDirectory("backstage-startup-");
        try {
            Path bin = Files.createDirectory(dir.resolve("bin"));
            // Exercise the generated shell locally without launching or killing any real daemon.
            for (String name : new String[]{"pidof", "nohup", "sleep"}) {
                Path mock = bin.resolve(name);
                Files.writeString(mock, "#!/bin/sh\nexit " + (name.equals("sleep") ? "0" : "1") + "\n");
                mock.toFile().setExecutable(true);
            }
            DaemonConfig original = new DaemonConfig();
            original.configPath = dir.resolve("daemon.json").toString();
            DaemonConfig.Link link = new DaemonConfig.Link();
            link.agentId = "agent"; link.linkKey = "key";
            link.lastCallCounter = "500"; link.resultCounter = "900";
            original.links.add(link);
            original.revokedLinks.add(DaemonConfig.fingerprint("revoked", "old-key"));
            original.save();
            DaemonConfig stale = new DaemonConfig();
            stale.configPath = original.configPath;
            DaemonConfig.Link low = new DaemonConfig.Link();
            low.agentId = "agent"; low.linkKey = "key";
            stale.links.add(low);
            DaemonConfig.Link revoked = new DaemonConfig.Link();
            revoked.agentId = "revoked"; revoked.linkKey = "old-key";
            stale.links.add(revoked);
            String script = Starter.writeConfigAndStart("/mock.apk", stale.toJson(), original.configPath,
                    dir.resolve("daemon.log").toString()).replace(Starter.DIR, dir.toString());
            Path scriptPath = dir.resolve("start.sh");
            Files.writeString(scriptPath, script);
            for (int i = 0; i < 3; i++) {
                ProcessBuilder p = new ProcessBuilder("/bin/sh", scriptPath.toString());
                p.environment().put("PATH", bin + ":/usr/bin:/bin");
                p.redirectErrorStream(true);
                Process child = p.start();
                String output = new String(child.getInputStream().readAllBytes());
                check(child.waitFor() != 0, "failed launch reported success");
                check(output.contains("startup not confirmed"), "missing startup error");
                DaemonConfig backup = DaemonConfig.load(original.configPath + ".prev");
                check(backup.links.get(0).resultCounter.equals("900"), "retry overwrote good counters");
            }
            DaemonConfig recovered = DaemonConfig.load(original.configPath);
            recovered.mergePrevCounters();
            check(recovered.links.size() == 1, "revoked link restored");
            check(recovered.links.get(0).lastCallCounter.equals("500"), "call counter rolled back");
            check(recovered.links.get(0).resultCounter.equals("900"), "result counter rolled back");
            Path prev = Path.of(original.configPath + ".prev");
            Files.writeString(prev, "{corrupt");
            try { recovered.mergePrevCounters(); throw new AssertionError("invalid backup accepted"); }
            catch (IllegalArgumentException expected) { }
            check(Files.exists(prev), "failed merge deleted recovery evidence");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
