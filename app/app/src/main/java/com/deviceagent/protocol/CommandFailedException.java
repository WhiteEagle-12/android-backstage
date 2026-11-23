package com.deviceagent.protocol;

public final class CommandFailedException extends RuntimeException {
    public final ProcessResult result;

    public CommandFailedException(ProcessResult result) {
        super("command failed (" + result.exitCode + "): " + result.command
                + (result.stderr.length == 0 ? "" : "\n" + result.stderrText().trim()));
        this.result = result;
    }
}
