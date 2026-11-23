# Alpha10: evidence returned with phone calls

## Contracts

- `android.location`: defaults maxAgeMs=10000, accuracyMeters=100, timeoutMs=10000; timeout bounded to 20000. Android's location providers run through the existing shell helper. No permission or device-setting changes. Age uses monotonic time, not the wall clock. A usable recent cached fix may satisfy the request and stays labeled `cached`. A result outside the limits has `ok:false`, a reason, and any available fix explicitly marked partial. Missing bearing/accuracy stays null; bearing describes movement, not the direction the phone faces. All subscriptions stop on return or interruption.
- `android.open_inspect`: app name/package or HTTP(S) URL. Starts or reuses the private display, opens, waits, then returns compact UI. Use `verify:{text:...}` or a package/activity predicate to name the desired state. The default check only establishes a focused window, not web-page completion.
- `android.inspect`: compact accessibility nodes and current activity on the given private display. `maxNodes` defaults to 80, maximum 300. Reports truncation; `android.ui_tree` remains the detailed read. `screenshot` accepts `never`, `always`, or `if_needed`.
- Input tools accept `verify`, `timeoutMs`, `inspect`, `screenshot`, `maxNodes`, and `expectedPackage`. Checks accept the existing text/resource/package/activity predicates or `{change:"window"}` / `{change:"screenshot"}`. A visual change proves only a change. Input is dispatched once. `inputDelivered` and `expectedResultObserved` remain independent, including timeout and inspection failures. An unknown input outcome must not trigger an automatic repeat.
- Private-display inputs check the active display, readable UI, focus, and optional expected package before dispatch. These checks add one UI snapshot and focus read. They cannot make a check and a tap atomic against another app changing its own screen.
- Screenshots retain image and source dimensions, axis scales, frame time/age and a `screenshotId`. Passing that ID to `android.tap` interprets x/y in the screenshot's coordinates. References expire after two minutes, with a 32-entry bound, and reject another display or changed dimensions. A reference does not assert that screen content has stayed unchanged.
- Results retain device identity at the gateway, display where relevant, observation/receipt times, elapsed time, outcome and job IDs. Image bytes occur once as image content, with metadata retained in structured output. Large text previews are explicitly shortened; complete structured results remain available. Short replies keep a text form for client compatibility.

Basic background calls now allow 45 seconds for mobile delivery and a result, instead of 12 seconds. This does not delay a returned result; it allows more time before declaring an unreachable phone. The encrypted deadline still bounds dispatch and expired writes are not retried.

## Limits

Accessibility inspection provides the text and controls exposed by the app. It does not promise a full browser DOM, cross-origin frames, link destinations, or labels for drawn map markers. Request a screenshot when those details are absent. This release adds no navigation-state or vibration-history tools.

## Validation

Host tests cover postcondition success and timeout, input receipts after lost observations, wrong-app blocking, invalid predicates, screenshot conversion/expiry/size mismatch, and location age/accuracy limits. Gateway tests cover mirrored schemas, budgets, image metadata, nested images, errors, and large-response formatting. The debug-only on-device harness runs against a shell-restricted test activity on its own private display, checks a public example page, and reports location freshness/accuracy without logging coordinates.

## Final phone validation (alpha11)

The final build adds a separate-task flag for URL launches. On Redmagic (NX809J), the on-device checks passed for private-display creation, readiness, tap plus postcondition, timeout preserving the input receipt, screenshot dimensions/frame time, wrong-app prevention, compact inspection, and URL delivery. The public Example Domain page matched the requested text in the combined call. A recent cached location was labeled cached (654 ms, reported accuracy 4.78 m). A request with maximum age 100 ms obtained a fresh fix (7 ms old, reported accuracy 4.397 m). No coordinates were written to the test log. The test display was released on completion.

Validated APK SHA-256: `d8eab0bafb55ab9d33a8322ed86480b7dc4191985c635bc8f6806a924e05d9a6` (11,507,167 bytes). Build and lint passed. Gateway suite: 46 passing tests; the extended device integration and contract checks also passed. Host and CLI suites passed.

## Final guard (alpha12)

Legacy direct callers must supply a positive display before `android.open` can run with verification or inspection. Invalid display choices now fail before opening anything; two host regressions prove zero dispatches for these cases. Build, lint, and host tests passed again.

The native connector also returned an actual test screenshot with all metadata intact: 608×1344 image, 1216×2688 source, scale 0.5, device identity, display 27, frame age, and screenshot reference. The test display was stopped afterwards.
