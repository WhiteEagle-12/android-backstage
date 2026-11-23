# RedMagic reliability repairs — 2.0.0-dev.5

Test device: RedMagic NX809J, Android 16 (SDK 36), shell UID 2000, no root. Phone access used the attached Backstage connection throughout; no host ADB or emulator.

## Confirmed causes and changes

- Android's notification dump contains `String (value)`. The parser previously accepted only `String(value)`, dropping titles and text. It now accepts spacing for String and styled text, including multiline Unicode values.
- The phone froze Keep while reporting it as a resumed foreground process on the private display. A frozen process could not answer accessibility requests. Reconnecting UiAutomation, and retaining it between calls, both failed to solve this. Neither experiment remains in production code.
- RedMagic can set `mFocusedApp=null` while `mCurrentFocus` still names the app. Before a tree read, Backstage now resolves the app only on the session-owned display and uses non-sticky `cmd activity unfreeze`. A popup without a component may use a single visible package on that same display; ambiguous packages cause no unfreeze. Display zero and unowned displays are excluded. Each shell lookup/unfreeze has a two-second limit.
- A rejected relay submission previously became an uncertain write with an invented recovery job. Definite HTTP rejections now preserve no-input certainty. Transient failures while polling an accepted job retry that same job, at most three consecutive failures and within its existing deadline; they never submit the action again.
- Retrying an identical request can replace an unknown receipt with a late authenticated job result. Missing jobs stay unknown. Synchronous exceptions no longer leave a stale in-flight promise. The job lookup checks sender identity, and audit records distinguish success, failure, and uncertainty.
- The upload limit previously appeared as an unknown phone write. Upload capacity and argument failures now preserve their real error and no-input outcome. The capacity message accurately tells callers to wait for expiration; installing an artifact does not remove it from storage.

## Checks

- Full JavaScript suites: 111 tests passed, including an encrypted MCP task result recovered after host restart with exactly one phone execution.
- Java host tests passed, including notification spacing/Unicode and private-display selection with display 0, similar display IDs, missing focus, popup focus, and ambiguous visible packages.
- Android debug build and lint passed.
- Real Keep workflow: independent reads, two 16-second idle pauses (including the search screen), search navigation, and exact `Backstage test café 日本語` field verification.
- Real Files workflow: independent reads, two 16-second idle pauses (including the open sort dialog), Downloads → More options → Sort by, then closing the dialog without changing the sort.
- Native fixture: 23 checks passed, including Unicode insertion/replacement, failed verification stopping a sequence, foreign-session rejection, scoped task reopening, screenshot coordinates, shell exit codes, and repeatable cleanup. The saved fixture screenshot was visually inspected.

## Practical limits

This is a temporary process resume, not a battery exemption or a global freezer change. Android can freeze the app again after use. Because an app process may serve several windows, unfreezing affects that process; input and screenshots remain restricted to the private display. The extra lookup and resume add a few hundred milliseconds to a typical tree read. The measured Keep reads were approximately 0.9–1.1 seconds in the first passing run.

The workaround addresses apps whose UI runs in their package-named process, as confirmed for Keep and Files on this phone. It does not promise to recover every OEM power policy or every app-specific process layout. Older Android versions without the unfreeze command still attempt the normal read. Uncertain writes are never automatically repeated.

No notes were created, messages sent, personal files deleted, sort settings changed, or browser tabs closed. Device checks released their private displays and session files. Logs and screenshots contain test evidence and belong in developer reports, not product screens.
