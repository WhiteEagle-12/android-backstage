# Input and result contracts

`android_type` enters Unicode through the focused field's accessibility action. The default `mode: "insert"` replaces the selection or inserts at the cursor. `mode: "replace"` sets the entire field. It does not change the keyboard or clipboard. Password fields and fields that hide their current selection require `replace`; fields without accessibility text input return a definite failure. Empty fields with Android's missing-cursor sentinel accept insertion. The implementation follows Android's [text and selection action contract](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_SET_TEXT).

`inputDelivered` is true only for an acknowledged action, false when input was not sent or was rejected, and null when delivery is unknown. A missing label returns `target_not_found`, `inputDelivered: false`, and `outcome: "input_not_sent"`. Bounds and visibility failures also precede dispatch. Verification failures retain the input receipt; callers must inspect before repeating a write.

Use Backstage's `code`, `outcome`, and delivery flags for recovery. ChatGPT's adapter can add `error_code: "INVALID_ARGUMENT"` to failed tool responses, overwriting a supplied `error_code`; this was observed in live testing. Backstage preserves the specific `code` in both text and structured results and keeps `isError: true` for failed actions. Hiding the failure flag to suppress a client label would mislead other MCP clients. A lookup miss requires reading the tree or scrolling before retrying.

`android_shell` reports a completed process as `ok: true`, including nonzero exits. Check `commandSucceeded`, `exitCode`, `stdout`, and `stderr`. Nonzero exits carry `code: "command_failed"` and `outcome: "command_exited"`. A launch failure or timeout remains a tool failure. The distinction avoids ChatGPT assigning its generic `INVALID_ARGUMENT` label to an ordinary process exit. Other failed commands keep `ok: false`.

Input replies contain receipts and requested verification by default. `inspect: true` adds compact UI; `diagnostics: true` includes the captured before and after trees. The underlying reader still bounds tree size. `verify` includes a compact observation; raw trees require diagnostics.

# Calls and metadata

`android_read_batch` runs one to eight supported diagnostic reads in one relay request, with at most four workers on the phone. All request shapes are checked before any read starts. Results retain request order and per-read outcomes. Supported reads are getprop, settings_get, battery, apps, package_info, ls, and status. Arbitrary shell commands, UI actions, and nested batches are excluded. Existing QA permits deny batches; this does not broaden their scope.

Separate calls still run in order under the link lock. That lock protects persisted encryption counters and result order. The batch avoids repeated network trips without weakening replay protection. UI work can use `android_open_inspect` or an input's `verify` option for combined action and observation.

`android_help` and `/work/health` expose the release and a SHA-256 fingerprint of the live hosted tool catalogue. Help also lists the parameter names for the main combined and input tools. Help cannot refresh a client's cached definitions. The server, Android catalogue and CLI contracts have automated parity checks.

After deploying a schema change, use the existing developer-mode ChatGPT connection's Refresh control, inspect the new metadata, and use a new conversation for new schemas. Follow the [OpenAI refresh procedure](https://developers.openai.com/plugins/deploy/connect-chatgpt#refresh-metadata). Published plugins need a new reviewed metadata snapshot.

Check a client export with:

```
node scripts/check-client-tools.mjs client-tools.json
```

Accepts a `tools/list` response or `{ "parameters": { "android_type": ["text", "display", ...] } }` captured from the client's visible metadata. A missing tool or parameter fails the check.

# Regression checks

- `bash app/hosttest/run.sh`: selection handling, surrogate boundaries, definite vs uncertain input outcomes, bounded real worker concurrency, batch failures, and existing crypto/replay tests.
- `node --test gateway/test/*.test.mjs cli/test/*.test.mjs relay/test/*.test.mjs`: contracts, client staleness detection, transport, reservations, authorization, and existing regressions.
- Android build and lint: `scripts/build-debug.sh`.
- `com.deviceagent.tools.ReliabilityDeviceCheck`: run via app_process as shell with the built APK on the classpath. It creates and destroys its own private display and tests exact Unicode readback, middle-selection replacement, cursor continuation, replacement mode, compact output, missing labels, shell exit 7, batch reads, and absence of an editable field. It returns a nonzero process exit on failure. Its activity exists only in debug builds and requires shell's DUMP permission.

The checks do not establish long-duration stability, network-loss recovery, or behavior in every custom editor. A late or lost transport reply can still have an unknown outcome; inspect its saved receipt and resulting state before retrying.

# Compact observations and local actions

`android_observe {display}` and `android_ui_tree {display}` return compact `controls` and deduplicated visible `content`. A control has an ID, label, role, and relevant state. Windows, packages, bounds, classes and shell internals require `diagnostics: true`. Default observations include at most 40 controls/content entries. Icon-only controls without an accessible label are counted as unlabeled; a tooltip or hint can supply a real label.

Tap with `{display, observation, target}`. References live for 60 seconds in a bounded 32-observation cache. Backstage compares the focused app's current tree before dispatch, then resolves and refreshes the exact native accessibility node in the action connection. It never falls back to coordinates. The window key combines app process and root-view identity: Android's temporary accessibility window numbers change when automation reconnects. Changed text, controls, bounds, selection or window identity invalidate the reference. This conservative guard can require a new observation after unrelated screen changes. Coordinate input remains available when the app exposes no usable reference.

`since: observation_id` returns changed/new controls and removed IDs/content when the app and screen remain substantially the same. A large change returns a full snapshot. Retained target IDs belong to the new observation too. Deltas reduce response size, but the phone still reads a complete bounded tree to validate the screen.

Input with `verify` returns a compact result and observation. Positive-duration waits require two matching stable observations; this adds a short settling wait so returned targets do not describe an opening animation. `timeoutMs: 0` performs a single check. Predicates include `appeared_since` with a text/resource selector, `target_disappeared: {observation,target}`, `field_equals: {resourceId,value}`, `screen_matches: {package,title,text}`, and nested `all`/`any` groups. Presence alone cannot prove an action caused a change. Truncated trees cannot prove a target disappeared.

`android_sequence` accepts one to twelve explicit tap/type/key/swipe/open/wait steps, validates every step before dispatch, and runs them under one device lease and a shared deadline (maximum 30 seconds). Each step can have a `before` condition and a verification condition. `observation: "previous"` and `appeared_since: "previous"` bind to the preceding observation. The first failed precondition, action or verification stops subsequent steps. The result contains step receipts and current state. The deadline prevents starting late actions; an OS call already in flight may finish after it. No automatic action retries, shell steps or nested sequences are supported.

# Browser profile and cleanup

Browser tasks use the existing `com.android.chrome` app and its signed-in profile. There is no isolated WebView, cloned profile, cookie copying, incognito mode or browser-data reset. Backstage never closes tabs. `android_browser` reads only Chrome on the requested display. `ready: true` waits for two matching visible page observations with web content present, the address bar not being edited, and no visible loading indicator. This is accessibility evidence, not a DOM readiness guarantee; `documentReadyState` stays null. Marker names and popup text are available only when Chrome exposes them through accessibility. Unlabeled icons are not assigned invented names.

`summary: "route"` reports only unambiguous observed destination, selected mode, duration and distance with source text and freshness. Missing or ambiguous values remain null. Its current duration/distance recognition covers English labels. `android_device_summary` returns battery and network system values with observation time. Package inspection defaults to package/version only; `fields` selects individual values, and permissions require diagnostics.

`android_task_begin` refuses to claim an existing display. It records the new display's ID and creation time and provides a private temporary directory. `android_task_finish` releases that display only if its identity still matches and removes that task's files. Cleanup is idempotent. It preserves all Chrome tabs and profile data; tests also reject cleanup of a replacement display with a reused ID.

# Timing

`timing: true` (also enabled by diagnostics) reports host queue wait, encrypted round trip, device queue wait, action execution, UI reads, UI settling, and screenshot capture/encoding where relevant. Local sequences aggregate their device phases. Total times contain the finer phases and should not be added to them. Screenshot bytes travel inside the encrypted response; separate upload and final client delivery times are not observable at the tool, so those values are null. Separate calls still serialize through the encryption/link lock. Local sequences and combined verification avoid repeated network trips without changing that lock.

On Android 14 and later, each bounded UI tool call shares one accessibility connection across its reads and actions, clears the accessibility cache before reads, and releases the connection in `finally`. Older Android versions keep separate connections because they lack the public cache-clear method. Android 13+ node reads disable prefetch to avoid work racing a disconnect.
