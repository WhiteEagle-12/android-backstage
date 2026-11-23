# Everyday task reliability

Source baseline: `014fa5f`, branch `codex/tool-contract-fix`.

## Changes

- Remember a field's hint under its accessibility window and node identity. When the delegate removes hint metadata and appends that known hint to both text and description, expose the value separately. Contacts on Redmagic reproduces this behavior. Keep the raw text for target identity checks. Hints have a bounded 512-entry cache. Fields first seen already filled may still lack enough evidence to separate a label.
- Accept spaces, nonbreaking spaces, parentheses and hyphens as display formatting only for Android phone input fields. Keep country prefixes, service codes, pauses and waits significant. Ordinary text stays exact.
- Sample after an action until the tree stays unchanged for 150 ms and at least 250 ms has elapsed, within a one-second polling budget. Explicit conditions retain their own timeout. Read-only observations retry an empty tree within 500 ms. Android calls already in progress may extend that budget. A quiet tree does not prove that the intended page loaded.
- Return an observation after a confirmed input whose value check fails. Do not repeat that input.
- Allow clickable row content to supply context for neighboring controls. Treat long-press controls as buttons. Report ambiguous controls and request a screenshot under `image: if_needed` when controls are unlabeled, ambiguous or truncated. No semantic labels are invented for pixels absent from accessibility.
- Use a single package-manager listing for package searches and selected fields. Keep missing packages as explicit failures. No per-package `dumpsys` loop runs for a search.
- Detect unavailable clipboard shell commands despite exit zero. Unsupported reads and writes fail with a practical next action; uncertain writes keep unknown delivery. Successful reads preserve whitespace except the shell's terminating newline. Clipboard access itself remains unavailable on Redmagic.
- Drain the notification dump without a pipe to `head`. Parse only the active notification section into records, preserve multiline text, and apply `max` to complete records. Exclude history and configuration. Unsupported formats fail; no diagnostic dump leaves the notification tool. This remains dependent on Android's dump format.

## Validation

- All Java host tests pass, including `UsabilityHostTest` for hint decoration, literal suffixes, number formatting, significant phone characters, hidden values, clipboard results, active notifications, multiline bodies, unsupported formats and delayed dialogs.
- All 96 JavaScript tests pass.
- Android debug build and lint pass with SDK 35. Existing lint warnings remain; no new customer-facing app screens were added.
- Redmagic: the candidate passed all 23 `HarnessDeviceCheck` checks. The private fixture screenshot was pulled and visually reviewed.
- Redmagic: `UsabilityDeviceCheck` passed 14 checks. Package search with selected fields took 186 ms on the first run and 188 ms on the final rerun. The final APK passed the same 14 checks after the multiline notification fix. It checked package versions, missing packages, unsupported clipboard failure, structured notification limits, actual Contacts hint handling, a later field observation, screenshot fallback for unlabeled controls, Cancel returning the discard dialog in the same call, and discarding the test contact.
- The phone-number comparison has host tests; the actual dialer has not been checked with this candidate.

All test contact drafts were discarded and private sessions released. No contact was saved, no message or email sent, and no call or purchase made.

## Remaining limits

Location still returns `location_service_unavailable` on Redmagic. A direct provider call confirms that the app's foreground service is absent while the separate daemon remains online. This patch does not change location service lifetime or permissions.

Outlook can omit visible rows from its accessibility tree. The image fallback helps the agent inspect such screens, but cannot guarantee detection when other accessible controls remain readable. That app-specific flow has not been rerun with this candidate.

The short polling window adds latency after actions, and `image: if_needed` may return more screenshots. Callers can request a shorter timeout or `observe: false`; explicit readiness checks remain preferable when the expected state is known.

Builds and receipts are under `~/android-backstage/usability-*`. Candidate execution uses its APK through `app_process`, independently of the installed daemon. The installed release has not been replaced by these checks.

## Release preparation

The release is `2.0.0-dev.3` / versionCode 203. Its rebuilt APK has SHA-256 `aac5c95921f147bc960e0fcb40232703b14707965c7b0aa18e26706d6d9e7c9e` and 11,700,117 bytes. Java host tests, 96 JavaScript tests, Android build and lint pass for this release. The advertised tool contract is unchanged: 29 tools, SHA-256 `3d1382fd998b1c6cf41d8900d9fe57f226a4d1bec8462d446f71d5e5c1f1ca39`. Existing hosted clients retain their endpoint and grants. Deployment receipts will be saved in `~/android-backstage/usability-deployment.json`.
