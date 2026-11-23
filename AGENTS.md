# Android Backstage

Use the named device through the relay or attached Backstage tools. Never use host ADB or an emulator. If several devices are linked and the task names none, ask which device to use. Never substitute a device that is offline or paused.

Use background operations when they can finish the task. UI work starts with `android.task_begin`. Pass its `sessionId` to every UI primitive, then release owned resources with `android.task_finish`. Do not wake, press HOME on, or capture the real screen as a side effect. Use a foreground lease or `android.handoff` with `confirmed:true` when the user explicitly requests main-screen use or the requested outcome logically requires it, including handing over a prepared form or opening a chosen video for the user to watch. Prepare privately first where practical; do not ask again when the request already authorizes that use. Browser tabs and profile data must remain intact.

Read `docs/TOOL-CONTRACT.md` for the current contract. The generator is `scripts/generate-tool-contract.mjs`; update it, then regenerate the native and CLI schemas. No Alpha16 tool aliases remain. Standalone actions and sequences use the same validator and executor. Keep summaries generic and application interpretation in the agent.

Use fresh observation targets. Changed controls and ambiguous selectors send no input. Writes need a unique `requestId`; an identical retry retrieves the first receipt. Do not repeat an uncertain write under a new ID without reading its state. Keep full trees and timing opt-in.

Build: `bash scripts/build-debug.sh` with `ANDROID_HOME` set. Host tests: `bash app/hosttest/run.sh`. JavaScript tests: `node --test gateway/test/*.test.mjs cli/test/*.test.mjs relay/test/*.test.mjs`. Device tests follow `.cursor/skills/qa-walk/SKILL.md`. They use a private test fixture and preserve the user's active screen and browser tabs.
