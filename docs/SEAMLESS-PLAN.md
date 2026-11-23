# Toward seamless setup and use

## Status (2026-10-03, 2.0.0-dev.23)

Done and tested on the RedMagic:

- Agents connect without codes: `connect --name` or `connect --all` sends a request; the phone shows a notification and an in-app card with the six digits and Allow/Deny. Without an existing connection the owner approves on a web page after signing in. Several requests can wait at once and one request can cover several agents.
- The connector installs Cursor, Codex, Claude Code, Grok, OpenCode, Gemini CLI and Antigravity (including T3 profiles). `serve --name` lets plugins start a connection by agent name.
- Accounts sign in with an emailed code, a passkey or a recovery code, on the web and in the app; any method adds a device, named after the phone.
- Approvals grant all devices by default; browser OAuth approvals can be sent to the phone by email.
- The app pins Connect an agent, shows one device name, shows assistants working or paused, pauses/resumes them and stops their screens, and manages email, passkeys and recovery codes.
- Release builds are non-debuggable and signed with the existing key; `scripts/publish-apk.sh` publishes them and the app updates in one tap. The daemon stops taking jobs during an update.
- The overlapping approval prompt is fixed, the QA package is removed, and Claude Code's plugin uses its own approved connection.

Still open: a production signing key (needs a one-time reinstall and re-link), per-grant session ownership for hosted assistants, accessibility reads across displays still taking turns, and two agents opening the same app on different displays.

Notes from connecting Claude Code, Grok, OpenCode and Antigravity to the RedMagic on 2026-10-03, and from making several agents work at once. Ordered by how much friction each item removes.

## 1. Connecting an agent

What it takes today, per agent:

1. Open Backstage on the phone, scroll to Connect an agent, choose the agent.
2. Copy the setup message on the phone.
3. Get that text from the phone to the computer where the agent runs, and paste it in.
4. The agent downloads `agent.mjs`, claims the invitation and prints six digits.
5. Return to the phone, compare the digits, tap Allow.
6. Edit the agent's MCP configuration (automatic only for Cursor, Codex and Claude Code).

Problems seen:

- **Step 3 is the worst part.** The invitation lives on the phone but the agent runs on the computer. Without a shared clipboard the user retypes 24 characters. The clipboard is also unreadable while the phone is locked, which blocked automation too.
- **One invitation per phone at a time.** Creating a new one cancels the last, so connecting four agents means four full rounds.
- **The approval prompt was hidden behind the waiting prompt.** A replaced dialog's dismiss listener cleared the visible flag after its successor showed, so the waiting dialog reopened on top and never returned the Allow prompt. Fixed in 2.0.0-dev.19.
- **Only three hosts install themselves.** Grok, OpenCode, Antigravity and Gemini need hand-written configuration, each in a different format and location (Antigravity's lives in T3's private profile).
- **Access scope is inconsistent.** Invitation grants are limited to this phone; OAuth grants (Codex, Cursor, ChatGPT) cover every device. The prompt does not let the user choose.

Proposal: start from the agent, approve from a notification.

- `npx @backstage/connect` (or the agent following `/work/connect/instructions`) asks the relay for an account-level request, with no code copied from the phone. It shows six digits and the relay pushes an actionable notification to every phone signed in to the account: "Claude Code wants access · 820385 · [Allow] [Deny]". One tap finishes it. The phone app never needs to be opened.
- Find the account without a code: the first request from a computer uses a device-flow URL that the user opens once in a signed-in browser. After that the computer holds an account-scoped enrolment key, so further agents connect with only the notification tap.
- Let several requests be pending at once. Show them as separate notifications and as a list in the app.
- Teach `install` every host it can detect (Grok `~/.grok/config.toml`, OpenCode `opencode.jsonc`, Antigravity/Gemini `mcp_config.json`, T3 profiles). Add `connect --all` to find installed agents and connect each with its own name in one pass.
- Put "This phone / All my devices" in the Allow prompt.

## 2. Sign-in

- **Claude Code's plugin OAuth never completed.** Its two registrations sit unapproved in `oauth.json`. Find why the localhost callback fails for Claude Code. Until then, the plugin shows "Needs authentication" forever even though a working connection exists.
- **Desktop approvals need a passkey in the browser.** The phone is already signed in to the account, so web OAuth approvals could be handed to the phone the same way: show digits in the browser and push the approval to the phone. No desktop passkey prompt.
- Three connection mechanisms exist side by side (hosted OAuth MCP, local `agent.mjs` connector, legacy CLI pairing links in `work/connections/phones`). Converge on one model (account-scoped client plus phone approval) and migrate the rest.

## 3. The app

- **One name per device.** The header said "phone · NX809J" in one launch and "Redmagic · NX809J" in the next: the relay's registered name and the account name differ. Self-update also needed `--device phone` while the account calls it Redmagic.
- **Connect an agent scrolls away** once a few assistants are listed. Keep it fixed at the bottom or in the header.
- **Updates.** Updating the phone today means building on the computer and running `self-update` with a link file hidden under `work/connections/phones/<id>.json`. Publish builds to the relay and let the app update itself, with a one-tap prompt or automatically when idle, keeping the existing restart-confirmation logic.
- **Leftover QA package.** `com.androidbackstage.qa` is installed next to the app. Remove it or label it clearly so agents and users do not pick the wrong one.
- **Debug build in daily use.** The installed app is debuggable and signed with the development key, so any shell on the phone can read its private state (`run-as` exposes the live invitation code). Ship a release build for daily use and keep debuggable builds for testing.
- **Active work is invisible.** With several agents on the phone at once, the app should show who is working now (agent, private or main screen, since when) with a per-agent stop. The data exists in session records.
- **Per-agent pause.** Pause stops every assistant. Add pause and stop per assistant on its Manage page.

## 4. Agent experience (done in this round)

- Each private task gets its own display; the limit is free memory, not a count.
- The relay runs up to eight jobs per phone; the gateway no longer serializes hosted agents.
- `task_share` and `task_begin({join})` let agents cooperate on one display; `task_list` recovers lost session IDs.
- `android.wait` no longer blocks other agents' reads.
- Polls from a daemon that has restarted are ignored, so self-update no longer loses jobs.

Still open:

- Accessibility reads are still one at a time across displays (one UiAutomation connection). Measure contention with several active agents; consider a connection per display if Android allows it.
- Two agents opening the same app on different displays pull it to one display. Detect it and return a clear `app_in_use` result, or launch a second instance for apps that support multiple windows (Chrome).
- All hosted agents share one link, so they share session ownership. Per-grant ownership would isolate them.
