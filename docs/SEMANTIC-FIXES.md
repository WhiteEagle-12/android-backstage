# Semantic actions and observations — dev.4

The Redmagic NX809J reproduced a Chrome link that accepted an accessibility click without navigating. The resolved node was the actual link. A coordinate touch on that same node navigated immediately. The change selects one input method before dispatch: WebView links receive one touch sequence, and native controls retain accessibility clicks.

## Behavior

- Web link roles include `link`, `visited link`, and `link visited`. A touch point must fit the current target, ancestor bounds and focused window, and avoid higher windows or unrelated accessible controls covering it. A failed or unverified dispatch never causes a second activation. Receipts expose the input method and whether the requested result was verified.
- Target identity uses the window's package/process/root key, type and bounds. A temporarily missing window title no longer rejects an otherwise identical target. The device trace captured this exact case with every control field unchanged. Titles remain literal observation metadata, and an explicit title condition still fails when the title is missing. The host regression reproduced the false rejection before this change.
- Compact observations keep the actual input target and its state. A lone switch can inherit its row label. A non-clickable radio can contribute state to the row that handles input. Missing state stays unknown. Changes to a child state invalidate the row's old target.
- Static text keeps its structural groups. Inline fragments such as `53` and `%` combine into `53%`. The battery page's remaining-time estimate stays separate from the percentage. Row descriptions stay with their controls; unrelated repeated text remains present. Shortened output reports truncation. Truncated observations cannot prove absence or prior absence.
- Verification discovery uses `conditions` and optional `match`, with typed leaves. State checks support `checked`, `selected`, and literal `stateDescription`. The strict contract still accepts the earlier leaf and all/any forms. The host and local client encode compact groups in the existing wire form for older Backstage 2 phones. State checks need dev.4 or later.
- Sequence arguments retain nested field types and descriptions. All steps still pass strict validation before input starts. The client contract checker now checks nested fields in tools/list, including sequence arguments.

## Costs and limits

Web links add a 50 ms touch hold and need a usable visible point. Accessibility bounds cannot reveal overlays that the app omits from its tree. Missing or unclear geometry therefore yields an actionable error; agents can obtain a fresh observation or scroll the control into view.

Grouping follows accessibility structure and nearby text bounds. It preserves relationships that the app supplies and does not infer an absent label or state. A late-loading battery estimate requires an explicit readiness condition when the task needs that value.

Keeping earlier verification forms increases the strict generated contract. Discovery exposes only the preferred form to reduce repeated schemas. Cached clients remain usable with their existing syntax, while refreshed clients receive the smaller schema. Typed discovery cannot express every cross-field rule in all clients, so the shared strict validator remains authoritative.

Measured UTF-8 JSON schema sizes for local MCP discovery:

| Surface | dev.3 bytes | dev.4 bytes | Reduction |
| --- | ---: | ---: | ---: |
| tap arguments | 6,825 | 4,221 | 38% |
| sequence arguments | 14,354 | 9,547 | 33% |
| default catalogue | 80,455 | 52,257 | 35% |

## Validation

The build uses Android API 35, Java 17 and the repository development signing key. Java host tests, Android assemble/lint, and all 103 JavaScript tests pass. Tests cover the captured battery page, state ownership, unknown state, text grouping, truncation, stale targets, bounds rejection, verification receipts, compact and legacy conditions, client discovery and strict sequence validation.

Device test receipts and APK hashes are retained in `~/android-backstage`. Candidate tests use the APK on the app_process classpath, a separate synthetic fixture package, and owned private displays. Browser tabs remain app-owned. The device harness checks Chrome's destination address and page heading together, waits for the source link to remain stable for 500 ms before testing an observation-bound tap, and waits for the battery estimate before inspecting its text groups. Per-tap timing excludes that source-readiness wait.

The final APK (SHA-256 `1a12de2ba52a5869a8e641ed6920fe103e77718d0fe920dcf4dc716919a7d440`) passed 55 semantic checks and all 23 existing device regression checks on Redmagic. Its six Chrome taps used alternating labels and observation-bound targets, each reached the intended address and heading, and took 1,325–1,458 ms inside the device (median 1,394.5 ms). No target rejection occurred in this final run. Both synthetic screenshots were inspected: the switch and radio states, long-press result, Unicode text and final sequence value matched the observations. Each harness released its private display.

Receipts: `semantic-redmagic-final5.log`, `semantic-dev4-build.log`, `semantic-js-final.log`, `semantic-final-fixture.png`, and `semantic-final-regression.png`. The earlier title trace and failing host regression are retained separately. Diagnostic target tracing runs only when the test process sets `backstage.traceTargets`; ordinary app and tool calls leave it disabled.
