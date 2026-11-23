# Android controls

Alpha7 adds seven tools to the CLI, phone MCP server, and hosted connector. Hosted names use underscores; native names use dots. The existing phone permissions still apply.

| Tool | Use |
| --- | --- |
| `android_wait_for` | Wait for text, a resource ID, a focused package or activity. All supplied conditions must match. |
| `android_task_status` | List tasks on a private display, with task/root IDs, package, activity, root visibility, and focused component. |
| `android_media` | Inspect active media sessions; play, pause, next, previous, or seek in one selected session. |
| `android_long_press` | Hold a point for a given duration. |
| `android_drag` | Hold a start point, then move to an end point. |
| `android_pinch` | Move two fingers together or apart around a center. |
| `android_multi_touch` | Move 1–5 fingers along independent paths at the same time. |

## Displays and handoff

Every new UI tool requires the active virtual display's positive ID. A missing, zero, stale, or different display fails before interaction. Hosted calls participate in the existing per-device reservation. Media actions also take a device reservation.

`android_vd_handoff` now accepts `taskId` from task status. An optional package filter must also match. The hosted call still requires `confirmed:true`, meaning the user requested the result on the phone screen. A stale ID, several matching tasks, or a task sharing a root stack fails before a move. Android's stack move could otherwise bring unrelated tasks onto the phone screen. After moving, Backstage brings the exact task forward through ActivityTaskManager and checks its new display; it does not relaunch the component.

The `visible` field retained for compatibility describes root-stack visibility, not independent visibility of every child. `rootVisible` names that distinction explicitly. `focusedComponent` reports the display's focused window component. The parser refuses to invent leaf IDs when Android omits them.

## Waits and assertions

```
android_wait_for({display: 2, text: "Ready", resourceId: "com.example:id/status", timeoutMs: 5000})
android_wait_for({display: 2, package: "com.example", activity: ".MainActivity", timeoutMs: 5000})
android_wait_for({display: 2, text: "Ready", timeoutMs: 0})
```

Text matches either text or content description, exactly and case-sensitively. `contains:true` enables substring matching. Text and resource ID must occur on the same node. A package condition requires the focused application; an activity also requires its package.

The default timeout is 5 seconds; the maximum is 20 seconds. Zero checks the predicate once after accessibility window tracking initializes. Window initialization can take about a second; zero does not mean zero execution time. Polling and lock acquisition use a monotonic deadline. Android service calls and connection setup can add scheduling overhead. The caller budget is 30 seconds.

A mismatch returns `assertion_failed` or `wait_timeout`. Unreadable or lost windows return `ui_unavailable`, concurrent reads can return `ui_busy`, and cancellation returns `interrupted`. Failed reads never fall back to the phone screen or a shell UI dump. The accessibility connection closes on every exit.

## Media

```
android_media({action: "status", package: "com.example.player"})
android_media({action: "play", sessionId: "<id returned by status>"})
android_media({action: "seek", sessionId: "<id>", positionMs: 60000})
```

Actions use Android media-session transport controls, not global media key events. Multiple matching sessions return `ambiguous_session` with candidates. Unknown session IDs return `session_not_found`; unsupported controls return `unsupported_action`. Session IDs are stable while a session and the daemon live; request status again after restarting the daemon or recreating a session.

`accepted:true` means the transport request was sent. Apps can ignore requests, and playback can change asynchronously; inspect status when the task requires proof. Status reports Android's playback state number, position, speed, update time, and supported actions. Seek positions use milliseconds. This tool may produce sound; the QA fixture never plays audio.

## Gestures

All points must lie within the target display. Validation finishes before any finger goes down. Duration is 1–5000 ms; drag/multi-touch holds are 0–3000 ms. Long press defaults to 800 ms; drag defaults to a 600 ms hold and 500 ms move. Pinch and multi-touch default to 500 ms.

Pinch spans are full distances between the two fingers. `angleDegrees:0` is horizontal. Multi-touch paths have 2–100 `{x,y}` points each, evenly spaced over the common duration. The input backend sends synchronized pointer-down, move, pointer-up and final up events with explicit display IDs. Failed or interrupted gestures send CANCEL to release active pointers.

## Device verification

Build the product and the separate `:qa` module using SDK 35 and JDK 17. Install the QA APK through the encrypted relay. Run `com.androidbackstage.qa.ControlsDeviceCheck` as shell UID through `android_shell`, with the product and QA APKs on the Android classpath. The runner invokes the actual product tool classes, creates its own private display, records events and silent media callbacks, and destroys its display in a finally block. It reads no product configuration or phone credentials.

The fixture refuses display 0 and has no network or playback code. No host ADB or emulator is involved. Gateway tests cover encrypted forwarding, device holds, typed schemas, confirmation, and propagation of failed assertions. The device test does not perform a real-screen handoff.
