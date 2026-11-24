package com.deviceagent.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.deviceagent.daemon.DaemonConfig;
import com.deviceagent.protocol.ProcessResult;
import com.deviceagent.transport.DeviceExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;

public final class ShellAccessTest {
    @Test
    public void shellRequiresExplicitOptInAndTogglesLive() throws Exception {
        Path tmp = Files.createTempDirectory("backstage-shell-test");
        DaemonConfig cfg = new DaemonConfig();
        cfg.configPath = tmp.resolve("daemon.json").toString();
        assertFalse(cfg.allowShell);

        DeviceExecutor fake = new DeviceExecutor() {
            @Override public ProcessResult exec(String command) {
                return new ProcessResult(0, "uid=2000\n".getBytes(StandardCharsets.UTF_8), new byte[0], command);
            }
            @Override public Map<String, Object> status() { return Map.of(); }
        };

        AndroidTools tools = new AndroidTools(fake, cfg);
        assertFalse(tools.allowShell());
        try {
            tools.shell("id");
            fail("expected shell_disabled when allowShell is false");
        } catch (InputNotSentException e) {
            assertEquals("shell_disabled", e.code);
        }

        assertTrue(tools.setAllowShell(true));
        assertTrue(tools.allowShell());
        Map<String, Object> out = tools.shell("id");
        assertEquals(Boolean.TRUE, out.get("ok"));
        assertEquals("uid=2000\n", out.get("stdout"));

        DaemonConfig reloaded = DaemonConfig.load(cfg.configPath);
        assertTrue(reloaded.allowShell);

        assertFalse(tools.setAllowShell(false));
        assertFalse(tools.allowShell());
        try {
            tools.shell("id");
            fail("expected shell_disabled after disabling allowShell");
        } catch (InputNotSentException e) {
            assertEquals("shell_disabled", e.code);
        }
    }

    @Test
    public void screenOffOptInAndSecurityFencesWork() throws Exception {
        Path tmp = Files.createTempDirectory("backstage-screenoff-test");
        DaemonConfig cfg = new DaemonConfig();
        cfg.configPath = tmp.resolve("daemon.json").toString();
        assertFalse(cfg.allowScreenOff);

        final boolean[] screenOn = {false};
        DeviceExecutor fake = new DeviceExecutor() {
            @Override public ProcessResult exec(String command) {
                return new ProcessResult(0, "ok\n".getBytes(StandardCharsets.UTF_8), new byte[0], command);
            }
            @Override public Map<String, Object> status() { return Map.of(); }
            @Override public Map<String, Object> interaction(String op, Map<String, Object> args) {
                if ("screen_state".equals(op)) {
                    return Json.mapOf("ok", true, "screenOn", screenOn[0], "locked", !screenOn[0]);
                }
                if ("packages".equals(op)) {
                    return Json.mapOf("ok", true, "packages", java.util.List.of());
                }
                return Map.of();
            }
        };

        AndroidTools tools = new AndroidTools(fake, cfg);
        assertFalse(tools.allowScreenOff());

        // Screen off + allowScreenOff=false -> blocked with screen_off_blocked
        Map<String, Object> blocked = tools.call("android.packages", Map.of("package", "com.example.app"));
        assertEquals("screen_off_blocked", blocked.get("code"));

        // Enable allowScreenOff -> allowed while screen is off, and persists to daemon.json
        assertTrue(tools.setAllowScreenOff(true));
        Map<String, Object> allowed = tools.call("android.packages", Map.of("package", "com.example.app"));
        assertEquals("succeeded", allowed.get("outcome"));
        assertTrue(DaemonConfig.load(cfg.configPath).allowScreenOff);

        // PrivacyFence always protects Backstage itself
        assertTrue(tools.privacyFence().isBlocked("com.androidbackstage.app"));
        Map<String, Object> selfPkg = tools.call("android.packages", Map.of("package", "com.androidbackstage.app"));
        assertEquals("blocked_app", selfPkg.get("code"));

        // FileChunks always blocks daemon internal files
        try {
            FileChunks.requireSafePath("/data/local/tmp/backstage/daemon.json");
            fail("expected path_blocked for daemon.json");
        } catch (InputNotSentException e) {
            assertEquals("path_blocked", e.code);
        }
        try {
            FileChunks.requireSafePath("/data/local/tmp/ok/../backstage/daemon.json");
            fail("expected path_blocked for traversal into backstage dir");
        } catch (InputNotSentException e) {
            assertEquals("path_blocked", e.code);
        }
    }
}
