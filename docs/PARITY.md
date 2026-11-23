# Human access parity

User acceptance: where an Android user can act, the remaining barrier must be the agent's ability or a demonstrated Android restriction, not a missing Backstage operation.

A public tool existing is not proof of parity. Each row needs a working public contract, failure receipts, and device evidence. An untested or app-specific limitation remains open. Shell workarounds are useful diagnostics, not a substitute for usable tools.

| Area | Required behavior | Evidence / state |
| --- | --- | --- |
| Visual interaction | Read pixels promptly without accessibility; screenshot-bound tap, hold, drag, pinch, paths, and keys | BUILT: image-only observations, keys and mapped gesture paths; 23 cross-app canvas/notification/deadline checks passed |
| Keyboard | Modifiers, editing, navigation, selection, function keys; release keys after failure | PARTIAL: Ctrl/Alt/Shift and editing/function keys built; 11 device checks passed; global keys await foreground mode |
| Text | Unicode in standard and custom editors; insertion, replacement, selection and editor actions | PARTIAL: explicit clipboard fallback passed on RedMagic; invisible-field Unicode acceptance passed |
| Windows | App dialogs, keyboard, permission windows and overlays on the owned display | BUILT: focused system windows and explicit windowId selection; 9 private dialog/panel checks passed, fixture screenshot inspected |
| Foreground access | Explicit leased main-screen mode, visible state and stop control; default private display | BUILT: explicit foreground lease, visible stop control, ownership tests; user authorized main-screen device checks |
| Notifications | Read, inspect actions, open, invoke, dismiss, reply; stable identities and no replay | BUILT: temporary shell-authorized listener; list capability passed; fixture action/reply/dismiss checks passed |
| Files and sharing | Large file transfer, inspect/move/rename, share text and files using granted content URIs | PARTIAL: bounded file reads and resumable CLI download built; sharing remains open |
| App navigation | Existing tasks, deep links, intents, document pickers, activity and process recovery | PARTIAL: task/URL/settings open and Keep/Files reads proven on dev.5 |
| Media and sound | Playback state/actions; hear permitted app output and provide audio input when Android permits | OPEN: playback controls only |
| Device controls | Rotation, volume, connectivity, permission and configuration flows; scope global actions explicitly | OPEN: shell and settings navigation only |
| Long tasks | Observe progress, cancel, recover connection, preserve receipts across interruption | PARTIAL: bounded sequences and durable relay receipts; long operations need audit |
| Workflow acceptance | Disposable note editing, custom canvas/editor, keyboard selection, file sharing, notification replies, nested navigation, recovery | OPEN |

## Implementation rules

- Background access remains the default. Foreground operations require an explicit session mode; they must never arise as fallback from failed background work.
- Screenshot input checks capture age, display incarnation/size and focused app. Pixel changes within the same app remain the agent's responsibility unless an explicit visual check is requested.
- Input acceptance and app result verification are separate. A failed receipt after dispatch stays unknown and is never repeated automatically.
- Exercise writes against owned fixtures or disposable objects. Do not send real messages, remove user data, or change user accounts during testing.
- Keep build/test detail in this file and test logs, not product UI.

## Starting point

Branch `feat/android-parity`, base `75a289f`, installed RedMagic build `2.0.0-dev.5`. RedMagic NX809J runs Android 16 (SDK 36), shell UID 2000 without root. Background Keep and Files reliability evidence is in `REDMAGIC-RELIABILITY.md`; it does not establish parity.

## Current evidence (not yet deployed)

- `KeyboardDeviceCheck` against the installed private `EvidenceTestActivity`: 8 checks passed with probe 1; 11 with probe 2, including Unicode insert/replacement, exact field-value checks, and clipboard restoration. Pixel captures took 367 ms and 410 ms.
- Probe 1 SHA-256 `267f8253316dbc88d6c9740feedc3a5b36656aa194d612c5b58a8422cb032393`; probe 2 `f078e962ae358814c114bcb4bfe519383922930c6c8e4208907f01e2ae00f1c5`. These are dex-only diagnostic APKs, not installed product updates.
- RedMagic grants shell `STATUS_BAR_SERVICE`, `MANAGE_NOTIFICATION_LISTENERS`, `READ_CLIPBOARD_IN_BACKGROUND`, and `WRITE_SECURE_SETTINGS`. It denies `ACCESS_NOTIFICATIONS`; direct `INotificationManager.getActiveNotifications` is therefore unsuitable. A temporary `NotificationListenerService.registerAsSystemService` succeeded without changing enabled listeners in settings.
- Android 16 AOSP `AccessibilityManagerService.requestImeLocked` excludes UiAutomation connections from input-method access. `android.type` retains the accessible-field path and adds explicit `focused:true`, `screenshotId`, `mode`, `clipboard:true`. Clipboard use is temporary, and a newer clipboard from another app must remain intact. Result acceptance remains unverified unless the caller supplies a check.
- `LocalSequence` no longer requires an initial tree when its first step can act independently. Both observation and screenshot references support earlier steps. Nested deadlines cannot extend their caller's deadline.
- During a transient disconnect, capability job `call-1a1cf2305f08b232` returned unknown, then reconciled to a completed read-only probe. Relay expiry was 05:47:33 UTC and app-process startup logged 05:48:06. The cause of that delay is not proven. Code inspection did prove that shell commands had independent timeouts. The candidate propagates the authenticated call deadline, keeps the CPU awake only during a bounded operation, and kills a timed-out shell process group. The user screen stays asleep.
- Current build/lint and 112 JavaScript tests passed before the latest device acceptance run. Pure Java tests cover pixel-only input, stale captures, mapped paths, visual sequences, reply schemas and nested deadlines.
- Separate fixture `com.androidbackstage.qa` APK SHA-256 `1533095a1551be2dd95474ce92af083311bd2d1a50dd115e253efcecf45884d0` was installed and granted its own notification permission. It records local notification actions/replies, canvas pointer events, inaccessible editor text and received shares. It refuses to open on display 0.

## Next acceptance frontier

1. Recover RedMagic by opening Backstage. Reconcile unknown job `call-267a32d40414c19b` and finish its session if one was created.
2. Run the authorized foreground test and positive clipboard checks while unlocked. Save and restore the previous screen; check the keyboard, navigation and Stop screen control.
3. Run the prepared dev.7 `FileDeviceCheck`, `InputDeadlineDeviceCheck`, window tests and foreground stop-during-input tests before deploying its matched host/native release. Verify supervisor recovery across the app upgrade.
4. Implement file sharing/content URIs, arbitrary intents, app-scoped sound capture/input and the remaining cancellation/device-control gaps. These are open implementation work, not demonstrated Android limitations.
5. Refresh the installed plugin skill and connected tool metadata after the matched release, and rerun complete workflows.

## September 14 continuation

- Probe 4 (`6d9015e12619972d09c13732ea2b0f923767039ae773627a4886224c9af8b7c2`) passed all 23 `ParityDeviceCheck` checks. `~/android-backstage/parity-canvas.png` was pulled and visually inspected. The launch readiness retry captures again without relaunching.
- First `WindowDeviceCheck` reached the fixture and exposed all controls; its expected labels used title case while Android rendered uppercase. The fixture acceptance expectations were corrected. This was a harness mismatch, not an input failure.
- The user explicitly authorized main-screen testing with “Yes” and “You may”, overriding the private-only QA restriction for this task. Foreground acceptance will save and restore the prior task, use only the QA app for screenshots, invoke Stop screen control, and prove later input is rejected while background reads remain available.
- Candidate version is now `2.0.0-dev.6` / 206. Product is still dev.5 until a matched host/native deployment. Latest 112 JS tests passed; final Java/build checks and device window/foreground checks remain in progress.
- Main-screen Stop is scoped to its session and does not pause background access. Global Pause access in the Backstage app still stops all access. Old stop actions cannot stop a replacement lease.
- The traced window failure is confirmed: Popup Window changed from Android ID 62999 to 63045 across connections while its root remained. The candidate now uses opaque window handles derived from display/type/process/root identity, reads root identities for the window list, and traverses only the selected control tree. Host tests cover reassigned numeric IDs, missing windows and distinct app processes/displays. Device rerun pending.
- Audio capability audit: RedMagic's shell UID has CAPTURE_AUDIO_OUTPUT, MODIFY_AUDIO_ROUTING, CAPTURE_MEDIA_OUTPUT, RECORD_AUDIO and CREATE_VIRTUAL_DEVICE for the primary user. These are not Android blocks. App-scoped output capture and input injection still need real implementation/probes. AOSP AudioMixingRule supports UID rules and recording-source injection; scrcpy's AudioPlaybackCapture demonstrates shell AudioPolicy capture. No user audio has been recorded.
- Probe 8 (`5d34d7f3642b04275f0edade756012dc2db36cce3b71e9c8ccf283f9d92573e4`) successfully selected and acted in the nonfocused popup through its stable handle. A separate read of fixture state confirmed both actions. The harness now waits for Android's asynchronous preference write before asserting; final complete rerun uses the full candidate APK.
- Hosted artifact cleanup is added with grant ownership, repeatable receipts and transfer-in-use protection. Three completed uploads from this task were removed locally to unblock staging before the new host tool is deployed; each ID and SHA-256 matched its recorded test artifact. No phone files or other clients' artifacts were removed.

- Full dev.6 APK passed all 9 window checks. The fixture screenshot `~/android-backstage/parity-windows.png` was pulled and visually inspected. All 113 JavaScript tests passed.
- Final background rerun found a locked-clipboard bug: Android returned null for the saved clip; paste reported accepted keys but the fixture received no text, and restoration was false. A read-only device probe confirmed `deviceLocked=true`, `keyguardLocked=true` and no clipboard access. The candidate now returns `phone_locked` before clipboard changes or input and checks readback before paste. This is separate from accessible-field entry. Android's [ClipboardService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/clipboard/ClipboardService.java) explicitly hides reads on a locked device. The user has been asked to unlock for positive clipboard and foreground acceptance. The earlier failed candidate may have replaced the clipboard with fixture text; its old contents were inaccessible and are not recoverable from this task.

- Final build 21 APK SHA-256 `59fb688e04e4c0321e9bc59aaa8d28852d1386764f92216bc71d97a5c8dbce0d` (12,215,545 bytes): full Java host tests, assemble and lint passed. The RedMagic passed 2 locked-clipboard rejection checks, 23 canvas/notification/deadline checks (including no text delivered while locked), and 8 accessible keyboard checks. Positive clipboard paste on this final APK awaits unlock; earlier probes passed 11 keyboard checks while unlocked. Final host instruction tests also passed (10/10).

## dev.6 rollout

- Commit `d6c94b1cb23da1e71f5e142d0f17cfafb8cd0b6f` deployed to the host. Health reports dev.6 and contract hash `539378cbecabafa6b2affe2b837e0c8ed0d0e3240e4de600609bc4683dbbbcb5`. Persistent host state was backed up and preserved.
- RedMagic installed APK SHA-256 `59fb688e04e4c0321e9bc59aaa8d28852d1386764f92216bc71d97a5c8dbce0d`, versionCode206. Its daemon restarted as PID24510 with dev.6 and the existing config. Old APK is `/data/local/tmp/backstage/rollback-205-parity.apk`.
- Supervisor restart failed: shell cannot start its unexported service; a same-app UID attempt with user0 was blocked by RedMagic AutoLaunch. The connection initially worked, then dropped before the recovery session returned. Request `parity-resume-supervisor-session`, job `call-267a32d40414c19b`, has unknown outcome. Do not repeat it; reconcile after connection recovery.
- User was asked and pinged to unlock and open Backstage. Foreground tests remain pending. Source inspection found `MY_PACKAGE_REPLACED` returned without restoring the service. A follow-up change restores it only when saved access, pairing and a link remain enabled, with a recovery notification if Android rejects it. It is not yet installed or device-verified.

## Prepared dev.7 changes (not deployed)

- Files: reads stay within 1 MiB, return per-chunk SHA-256 and a file version, and reject changed sources. Whole-file reads above that size return chunk instructions rather than allocating unbounded memory. The CLI resumes durable verified chunks after a connection failure, checks saved bytes, and publishes without replacing an existing file. File IDs check identity/size/timestamps; they do not freeze concurrent app writes. Use completed source files. Abrupt process death may leave a lock requiring inspection before removal.
- Input: tap selects a clear visible part of a partly covered target. Gestures and key holds check the enclosing deadline throughout execution and release fingers/modifiers when interrupted. A background watcher checks foreground permission during input so provider reads do not delay individual touch frames. Loss of permission stops the input. Stop and renew now share one app-side lock, preventing a concurrent renewal from restoring a stopped lease.
- Upgrade: restore the existing supervisor on MY_PACKAGE_REPLACED only when saved user authorization, linking and pairing are all present. Show a short recovery notification if Android refuses. Reboot behavior remains paused.
- Host evidence: all 118 JavaScript tests passed; pure Java tests cover file ranges/versions/integrity, resumable downloads and interrupted holds. The previous one-millisecond deadline test assumed an initial observation that no longer exists; it now uses an already-expired caller deadline and asserts zero backend calls deterministically.
- Device acceptance classes for files and deadline interruption are prepared. They have not run because RedMagic remains offline. Foreground stop response and service recovery also need device evidence before this release ships.

- Final prepared dev.7 APK: `~/android-backstage/android-backstage-2.0.0-dev.7.apk`, 12,244,269 bytes, SHA-256 `0e546ea4d28247f741b055e56198d9a163ff9561a7827ee525d5033296d8c18b`. Final full Java host suite, assemble and lint passed. All 118 JS tests passed before the final instruction sync; its 11 focused contract/instruction checks passed afterward. The phone skill validates. None of the dev.7 device checks has run.


## September 14 main-screen access follow-up

- RedMagic returned online after the user opened Backstage. Earlier recovery job `call-267a32d40414c19b` still reports unknown; its lease deadline has passed. It was not repeated.
- This task's attached `android_task_begin` schema lacks `mode` and `confirmed`, despite the deployed dev.6 catalogue exposing both. Passing these fields through the stale attachment returned `mode:private`, display6. Session `session_153db48d3f504f97b5250fc4c9b5aab0` was finished. Main-screen access must never be inferred from a successful begin receipt alone.
- Refreshed the personal plugin through `codex plugin add android-backstage@personal`, version `0.3.1+codex.20260914180035`. Its installed skill matches deployed dev.6 features and explains persisted user authorization, foreground response checks and stale-tool recovery. Repository instructions retain prepared dev.7 features. A fresh task still must verify the refreshed tool fields; this running task retains its old schema.
- The first foreground harness used the wrong window type (`input_method` instead of the actual `ime`). Corrected the harness and loaded only that corrected class ahead of the installed dev.6 APK. All 15 foreground checks passed: explicit display0 lease, visible stop notification, fixture launch, Unicode typing, keyboard window and controls, Home, existing-task reopening, previous-task restoration, Stop rejecting subsequent input, and continued background access. This tests the native implementation; it does not prove the stale public attachment was refreshed.
- Evidence: phone `/data/local/tmp/backstage-foreground-corrected-check.log`; fixture-only screenshot `~/android-backstage/parity-foreground.png` pulled and inspected. Test cleanup finishes the foreground lease and stops only the QA fixture. Eleven focused contract tests passed. Dev.7 remains undeployed and its input-interruption/file acceptance remains open.

## September 14 test-user release

This section supersedes the earlier prepared-release status. Dev.7 is installed and running on RedMagic; its own-APK update path restarted the engine and restored the existing supervisor through a temporary private activity when the OEM rejected a direct service start. Saved authorization and links remain in place. No main-screen recovery was needed.

Device checks passed: typed intents (3), two-file URI sharing with exact hashes (8), interrupted input/deadlines (7), device controls and lease enforcement (7), asynchronous audio capture/input (8), plus the earlier foreground suite (15). Audio input holds its silent route after playbackFinished until explicit stop or lease expiry, so callers can finish the target recorder before its microphone resumes. File ranges/resume and display-drain regression have host coverage. The full JavaScript suite passed 119 checks; Android host tests, 8 JVM tests, build and lint passed.

Publication candidate APK SHA-256: 85e0c15577739812fd7be374a8607388a4c9e2c4c456390dca2a29adb8939bff (12,444,141 bytes). The catalogue exposes 32 public tools. Private Backstage is the default; foreground use is authorized explicitly or by a requested outcome that logically needs it.

Limits: this existing task still has a cached MCP schema, so current foreground/audio/device-control connector discovery needs a fresh attachment. Real-device native checks prove engine behavior, not refresh of that cache. Other phone models and app-specific behavior are untested. The original full-phone freeze trigger remains unresolved; see FREEZE-2026-09-14.md. Do not call universal parity or freeze prevention complete from this test-user release.
