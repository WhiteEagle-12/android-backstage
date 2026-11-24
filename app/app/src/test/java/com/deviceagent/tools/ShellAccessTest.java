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
}
