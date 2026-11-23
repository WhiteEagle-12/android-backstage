package com.deviceagent.transport;

import com.deviceagent.protocol.ProcessResult;
import java.util.Map;

public interface DeviceExecutor extends AutoCloseable {
    interface AccessibilityScope extends AutoCloseable { @Override void close(); }
    /** One bounded UI call may share its connection; implementations release it on close. */
    default AccessibilityScope accessibilityScope() { return () -> {}; }
    /** Keep the CPU running for the bounded call without waking the user's screen. */
    default AccessibilityScope executionScope() { return () -> {}; }

    ProcessResult exec(String command);

    Map<String, Object> status();

    default ProcessResult exec(String command, long timeoutMs) {
        return exec(command);
    }

    default byte[] pull(String remotePath) {
        throw new UnsupportedOperationException("pull is not supported by this transport");
    }
    default Map<String,Object> pull(String remotePath,Long offset,Integer length,String fileId){
        throw new UnsupportedOperationException("Update Android Backstage to download file chunks");
    }

    default void push(String localPath, String remotePath) {
        throw new UnsupportedOperationException("push is not supported by this transport");
    }

    /**
     * Headless virtual display ops: start | stop | status | screenshot.
     * Lives in the shell-uid process because only shell may create a trusted display.
     */
    default Map<String, Object> virtualDisplay(String op, Map<String, Object> args) {
        throw new UnsupportedOperationException("virtual display is not supported by this transport");
    }

    /** Accessibility-layer UI reads (no uiautomator idle wait): tree | probe. */
    default Map<String, Object> accessibility(String op, Map<String, Object> args) {
        throw new UnsupportedOperationException("accessibility reads are not supported by this transport");
    }

    default Map<String,Object> interaction(String op, Map<String,Object> args) {
        throw new UnsupportedOperationException("Update Android Backstage to use " + op);
    }

    @Override
    default void close() {}
}
