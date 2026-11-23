package com.deviceagent.app;
public final class AgentInstructions {
 private AgentInstructions(){}
 public static final String TEXT="""
Use the named device. Never substitute another device when it is offline or unavailable. Do not use host ADB.

Begin UI work with android.task_begin. Pass its sessionId to each primitive and finish with android.task_finish. Private mode is the default. Use mode:foreground and confirmed:true only after the user authorizes main-screen control. It may interrupt them; a visible notification provides Stop screen control. The session owns its display access, lease, and temporary files. Disconnects expire the lease and release those resources. Never close browser tabs or clear profile data as cleanup.

Each private task gets its own display, so other agents may be working on the phone at the same time. Never finish or act on another agent's session. To work together on one display, the session's owner calls android.task_share and gives the joinCode to the other agent, which passes it to android.task_begin as join. Agents on one display take turns per action, so observe again before acting when another agent may have changed the screen. A joined agent's android.task_finish only leaves the session.

android.observe returns labels, roles, context, target IDs, and windows. Pass windowId from that list to read or act in a keyboard, dialog, or overlay; omit it for the focused window. Tap with observation and target, an exact unique selector, or screenshot coordinates. A stable target survives unrelated changes; changed controls, windows, or app identity require a fresh observation. android.type focuses a field target, replaces or inserts Unicode text, and checks the value. For an editor without accessibility, use focused:true, screenshotId, mode and clipboard:true. This temporarily uses the shared clipboard and restores it unless another app replaces it. Verify the result from pixels when the field hides its value.

Use verify: {conditions: [{kind: "exists", selector: {label: "Ready"}}], match: "all"}. Supply one to eight conditions; match defaults to all, or use any. Kinds: exists, absent, appeared, field, state, window, stable. State checks require selector and checked, selected or stateDescription. Stability requires selectors or an accessibility region. A condition already true does not prove the action caused it. Region stability describes accessibility content, not pixel-only animation.

android.sequence runs at most twelve explicit primitives under one deadline and stops on failure. Arguments match standalone primitives. Observation and screenshotId references may be previous or step:0. Actions and values stay literal. No loops or scripts.

Supply a unique requestId for every write. Reuse it only with identical arguments to retrieve the first outcome while the device keeps its receipt: normally 24 hours, and on a very busy device the oldest completed receipts are dropped first. Never reuse an old ID for a new operation. Input delivery and verification are separate. verification.status is verified, failed, or unverified; an accepted tap without a check remains unverified. An unknown write must not be repeated under a new ID without checking the device state. Read retries are bounded.

image=only captures pixels without reading accessibility. It cannot be combined with verify, since or debug. image=always or if_needed adds a screenshot alongside controls, with source dimensions, capture time, and coordinate scaling. Use screenshotId for screenshot coordinates, gestures or keys. Captures check age, display size and focused window. They do not prove unchanged pixels within the same app. Keyboard input supports modifiers and repetition; global keys and Meta shortcuts require a foreground session. detail=debug adds full diagnostics; timing=true adds measured timing.

List notifications to obtain current notificationId, actionId and reply fields. Use those for open, action, reply and dismiss; changed notifications reject input. Check app state after an accepted action. Use location freshness and accuracy, media session selection, and encrypted file transfer with integrity checks. Developer and maintenance tools are separate from the default surface. Prepare on the private display, then use android.handoff with confirmed=true only when the user asked to see the result on the phone screen. Never wake, press HOME on, or capture the real screen as a side effect.

For files larger than 1 MiB, use android.pull with offset:0,length:1048576. Continue with nextOffset and fileId until eof; verify each chunk SHA-256. A changed source requires a fresh download. Clipboard reads and focused paste require an unlocked phone.
""";
}
