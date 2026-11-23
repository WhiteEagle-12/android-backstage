package com.deviceagent.shell;

import android.os.Bundle;
import com.deviceagent.protocol.ProcessResult;
import com.deviceagent.transport.DeviceExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DeviceExecutor for code that already runs inside the shell-uid daemon: no
 * Binder hop, no 1 MB transaction limit, and nothing an OEM battery manager can
 * freeze. Commands run with ProcessBuilder; the virtual display and
 * accessibility hosts are called directly.
 */
public final class LocalExecutor implements DeviceExecutor {
    private static final long DEFAULT_TIMEOUT_MS = 20_000L;

    private final VirtualDisplayHost virtualDisplay;
    private final AccessibilityHost accessibility;
    private final InteractionHost interaction;

    public LocalExecutor(VirtualDisplayHost virtualDisplay, AccessibilityHost accessibility, InteractionHost interaction) {
        this.interaction = interaction;
        this.virtualDisplay = virtualDisplay;
        this.accessibility = accessibility;
    }

    @Override
    public AccessibilityScope accessibilityScope() { return accessibility.openScope(); }
    @Override public AccessibilityScope executionScope() { return interaction.executionScope(); }

    @Override
    public ProcessResult exec(String command) {
        return exec(command, DEFAULT_TIMEOUT_MS);
    }

    @Override
    public ProcessResult exec(String command, long timeoutMs) {
        return toResult(LocalShell.run(command, null, timeoutMs), command);
    }

    @Override
    public byte[] pull(String remotePath) {
        return java.util.Base64.getDecoder().decode((String)pull(remotePath,null,null,null).get("dataBase64"));
    }
    @Override public Map<String,Object> pull(String remotePath,Long offset,Integer length,String fileId){return com.deviceagent.tools.FileChunks.read(remotePath,offset,length,fileId);}

    @Override
    public void push(String localPath, String remotePath) {
        Path from = java.nio.file.Paths.get(localPath);
        Path to = java.nio.file.Paths.get(remotePath);
        if (from.toAbsolutePath().equals(to.toAbsolutePath())) return;
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            ProcessResult r = exec("cp " + com.deviceagent.protocol.ShellCommand.quote(localPath) + " "
                    + com.deviceagent.protocol.ShellCommand.quote(remotePath));
            if (!r.ok()) throw new RuntimeException("push failed: " + localPath + " -> " + remotePath + ": " + r.stderrText());
        }
    }

    @Override
    public Map<String, Object> virtualDisplay(String op, Map<String, Object> args) {
        return Bundles.toMap(virtualDisplay.handle(op, Bundles.fromMap(args)));
    }

    @Override
    public Map<String, Object> accessibility(String op, Map<String, Object> args) {
        return accessibility.handleMap(op, args);
    }

    @Override
    public Map<String,Object> interaction(String op, Map<String,Object> args) { return interaction.handle(op,args); }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", true);
        status.put("transport", "daemon");
        status.put("backend", "shell uid " + android.os.Process.myUid() + " (daemon)");
        status.put("root", false);
        return status;
    }

    static ProcessResult toResult(Bundle reply, String command) {
        byte[] stdout = reply.getByteArray("stdout");
        byte[] stderr = reply.getByteArray("stderr");
        int exit = reply.getInt("exit", -1);
        String error = reply.getString("error");
        if (error != null && !error.isBlank() && (stderr == null || stderr.length == 0)) {
            stderr = error.getBytes(StandardCharsets.UTF_8);
        }
        return new ProcessResult(exit, stdout == null ? new byte[0] : stdout, stderr == null ? new byte[0] : stderr, command);
    }
}
