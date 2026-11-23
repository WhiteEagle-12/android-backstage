---
name: pick-device
description: Select exactly one linked Android device for a phone-control or device-test task.
---

Read the attached Backstage device list or `node cli/backstage.mjs devices`. Match the user's name or alias. If one device is linked, use it; if several are linked and none was named, ask. Never substitute another phone when the chosen one is offline, paused, or unavailable.

Use the attached connection when the CLI has no saved access. Do not copy another client's keys or reset pairing. Ask the user to resume access in Backstage when required.

Keep UI work on a task-owned private display: `android.task_begin`, `sessionId` on primitives, then `android.task_finish`. Use `android.handoff` only when the user asked to see the prepared result on the phone screen. Never close browser tabs or clear profile data as cleanup. No host ADB or emulators.
