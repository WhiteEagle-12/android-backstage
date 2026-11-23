---
name: qa-walk
description: Build and test Android Backstage or an Android app through the relay on a named phone's private display.
---

Build with `bash scripts/build-debug.sh` and the installed SDK in `ANDROID_HOME`. Run host tests and relevant JavaScript tests before a device check. Never use host ADB or an emulator.

Resolve one test device with the attached Backstage device list. Use the named device; ask when multiple devices are linked and none was named. Do not borrow another client's credentials. Use the connected plugin if the CLI has no saved link.

For app installation, use the CLI `install` command or upload a verified artifact and call `android.install` with `source`. Begin a task, open the package or fixture activity with `android.open`, and use generic readiness checks. `android.logs` with `kind: "crash"` reads crash records. `android.developer` provides instrumentation when enabled.

Use `android.observe` and observation-bound `android.tap` or `android.type` calls on the session's private display. Save fixture screenshots with `image: "always"` for visual review. Never use personal app content as a generic connection test. Preserve the phone's foreground and all browser tabs.

Run `com.deviceagent.tools.HarnessDeviceCheck` through the relay with the candidate APK on the `app_process` classpath. It tests the rebuilt engine against the installed fixture. Finish its session and remove only test-owned files. No action may use display 0.

Updates use optional `android.maintenance` and can lose their receipt during restart. Check the same device's version and boot identity before considering another write; never blindly repeat an update. Changing the tool contract requires deploying the matching native and host versions together.

Report the tested build, real pass/fail outcomes, screenshots inspected, and any remaining limits. Do not claim checks that did not run.
