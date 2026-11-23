# Android Backstage setup redesign

The Android app now guides a new user through connecting an assistant and approving Android phone access. A verified first link enables startup after phone approval; the user does not need a separate Start step. The home screen shows actual relay readiness and a prominent Pause control. A pause persists across activity recreation. A phone reboot leaves access paused until the user resumes it. An app update preserves the owner’s existing access choice.

The default server is `https://relay.android-backstage.com`, which is the server configured for this installation. Change server remains available during pairing. The CLI prints an `androidbackstage://connect` link that fills in the server and code; the phone always requires the user to review and tap Connect. This custom link contains a short-lived pairing secret, so share it only with the phone owner.

Android's Wireless debugging approval remains an OS requirement. The app explains notification permission before requesting it, lets the user enter the Android code while Settings stays open, and provides manual entry and USB startup alternatives. It does not require a VPN.

The original supplied artwork is preserved at `app/app/src/main/res/drawable-nodpi/backstage_art.png`. Android scales and insets it inside an adaptive launcher icon. The app uses native controls, light and dark colors, scrollable layouts, system insets, labeled fields, and touch targets of at least 52dp. No runtime dependencies were added.

The home page lists each connected client with Pause, Rename, Limit to an app, and Remove controls. Android access and Help are one tap away. Raw ports, local access tokens and diagnostic logs live under Help → Advanced. Full access and removal each have a clear confirmation; Cancel never grants access. Existing revocation acknowledgement remains required.

## Verification

- `bash app/hosttest/run.sh` includes secure URL validation, startup consent combinations, daemon version checks, secret-safe errors, and an actual SPAKE2 exchange with a fake relay transport. It covers wrong confirmation, timeout, single-use claims, token isolation between servers, and linking without a local daemon.
- Node relay and CLI suites: `node --test relay/test/*.test.mjs cli/test/*.test.mjs`.
- Android: `cd app && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`.
- `com.deviceagent.app.SetupUiCheck` is a dependency-free instrumentation runner for an unlinked development install. It creates a private virtual display and checks setup navigation, validation, Help, Android access help, and deep-link review. It saves app-view renders under the target app's external `files/ui-review` directory. It never opens an activity on display 0. The runner refuses an existing linked install.

The source import did not include the signing key for the original APK. A new local development key is stored in the ignored `app/keystore/development.jks`; keep this file for future updates. This machine also has a protected backup at `~/.config/android-backstage/development.jks`. Replacing an APK signed with the old key requires a fresh installation or access to the original key. Do not uninstall an existing app without the owner's approval.


## Device verification on September 11, 2026

Installed on the owner's physical NX809J after approval. Eight setup checks passed on a private display. The test caught and fixed routing that hid phone-access setup from an unlinked user. The instrumentation runner uses Android CTS's test-only `INTERNAL_SYSTEM_WINDOW` permission to launch on its private display; the production app's permissions did not change.

A new code completed the encrypted exchange between the phone and the configured public relay. The existing Android ADB pairing keys were restored from a private backup. The old Tailscale relay link was not reused. A public-relay shell command returned `NX809J`; normal app launch reported alive with no crashes, and the live home screen showed “Ready for your assistant.” The temporary test APK and displays were removed. This verification did not use or capture the main phone screen.

## Alpha 3: connection names and fewer menus

A connection identifies an app or computer, not a model or chat. The home page explains that all chats and models using a connection share its access. Names are owner-chosen labels. The real links are now named **Codex on this computer** and **ChatGPT Work**. Each has its own encryption key and permission.

Client controls moved from nested menus onto the home page. Changes preserve the visible connection card’s scroll position. Limited access has an installed-app picker; manual package entry remains available for apps without a launcher. Android package visibility is limited to launcher apps, with no QUERY_ALL_PACKAGES permission. The 15-minute test mode still blocks shell and arbitrary input.

The provided icon remains unchanged. Version 1.1.0-alpha3 was built, linted, and installed through the existing encrypted relay without host ADB. Both saved connection names survived the update. The local connection successfully paused the Work link: Work commands were denied while the local model read still succeeded. Full access was then restored, and a real ChatGPT Work conversation in Chrome completed model and fresh-string echo checks. See `CHATGPT-WORK.md` for the live receipt.

The unlinked-install instrumentation runner was updated for the flat navigation but was not rerun against this linked personal phone. Alpha 3 uses a manual relay-driven UI walk on a hidden display and the host, relay, CLI, and gateway test suites.

The installed-app picker was opened again after the interruption: selecting Android Backstage and canceling worked without changing permission. App crash records were empty. The daemon log showed a relay poll SocketTimeoutException at 21:21:41 UTC followed by a successful reconnect at 21:21:44, with the same process still running. The evidence points to a transient network interruption, not a confirmed app-picker crash.

Final installed-build checks passed: both client names persisted; Work pause/resume kept its card at the same vertical position; Remove named the affected connection and Cancel preserved it; Help opened directly from home; the picker selected Android Backstage by its display name and Cancel preserved permission. Both links were left with full access. The final crash report contained zero app crashes. The private test display was stopped after the walk. Screenshots and `alpha3-ui-checks.json` are under `~/.local/share/android-backstage/verification/`.
