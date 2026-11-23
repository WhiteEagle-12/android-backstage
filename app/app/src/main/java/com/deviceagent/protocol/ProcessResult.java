package com.deviceagent.protocol;

import java.nio.charset.StandardCharsets;

public final class ProcessResult {
    public final int exitCode;
    public final byte[] stdout;
    public final byte[] stderr;
    public final String command;

    public ProcessResult(int exitCode, byte[] stdout, byte[] stderr, String command) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? new byte[0] : stdout;
        this.stderr = stderr == null ? new byte[0] : stderr;
        this.command = command;
    }

    public String stdoutText() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public String stderrText() {
        return new String(stderr, StandardCharsets.UTF_8);
    }

    public String trimmedStdout() {
        return stdoutText().trim();
    }

    public boolean ok() {
        return exitCode == 0;
    }

    public ProcessResult requireOk() {
        if (!ok()) {
            throw new CommandFailedException(this);
        }
        return this;
    }
}
