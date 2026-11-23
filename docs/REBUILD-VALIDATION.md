# Backstage 2 rebuild validation

The new contract is deployed on Redmagic and the host as `2.0.0-dev`, commit `79fc96a911950fb21bdafe865094e42a03602cfe`. The host preserved its persistent state and saved a rollback backup. Redmagic reconnected, public host health passed, and an encrypted file read returned the new daemon's startup log.

The current conversation still caches Alpha16 tool schemas. The connector must reload its tool list before the new required write arguments can pass through. A post-deployment shell check was rejected before input because the cached client omitted `requestId`. Nova and the tablet were not updated.

## Passed locally

- All Java host tests pass, including the new primitive, observation, screenshot-coordinate, scroll, sequence, session lease, and cleanup tests.
- 89 JavaScript tests pass; none failed or skipped. These include enrollment, grant isolation, revocation, transport, file encryption/integrity, model schemas, and durable write receipts.
- Android debug APK build and lint pass against SDK 35 with minimum API 30. Existing lint warnings remain.
- Both updated repository skills pass their frontmatter validation.
- `git diff --check` passes.

## Redmagic device QA passed

Redmagic (NX809J) passed all 20 checks in `HarnessDeviceCheck` on 2026-09-13, with exit code 0. The candidate ran through `app_process` with its APK on the classpath against the installed `EvidenceTestActivity`, on a private display. The screenshot was pulled and reviewed. All four test sessions reached `finished`; their temporary files and the device hold were released.

The first run exposed a test assumption: Redmagic reports the fixture's button labels in uppercase. The test now uses the actual observed labels. Another run correctly rejected an insertion target while the selected field changed. The test now waits for that field's value and scoped stability before inserting. The full rerun passed without changes to the action engine. Earlier failed-run logs remain in the artifact folder.

Coverage includes session creation, open/readiness, compact status, stable targets across unrelated changes, selector/URL failures, field-targeted Unicode, selection insertion, sequence stop-on-failure, ownership, screenshot metadata, task inspection, shell streams, and repeatable cleanup. Native gesture timing, scrolling behavior across OEM apps, and long disconnect cleanup remain unproven on a phone. Host tests cover their deterministic logic.

These 20 checks ran before deployment against the exact APK later installed on Redmagic. Post-deployment checks cover host health, device reconnect, and encrypted reads; a repeat UI run through the connector awaits its metadata refresh.

## Artifacts

Saved under `~/android-backstage`:

- `android-backstage-2.0.0-dev.apk`
- `rebuild-validation.json` — size, SHA-256, and check status
- `rebuild-host-tests.log`
- `rebuild-js-tests.log`
- `rebuild-android-build.log`
- `rebuild-redmagic-device.log` — 20 passing checks and exit code 0
- `rebuild-redmagic-fixture.png` — reviewed private fixture capture
- `rebuild-redmagic-build.log` and `rebuild-redmagic-lint.log` — final test APK build and lint

## Contract choices

No old tool names or parameter aliases remain. Refresh metadata and deploy the matching host and native versions together before using the new contract.

Sequences reference previous observation IDs only; action values remain literal. Generic stability measures selected accessibility controls or a region's accessibility content, not pixel-only animation. Targets retain a 60-second expiry. Completed write receipts remain available for 24 hours, or until a journal over 16,384 receipts / 128 MiB evicts the oldest completed ones. Package operations, instrumentation, and maintenance are available only through the optional local developer surface.

These choices and the operational contract are documented in `docs/TOOL-CONTRACT.md`. Session expiry has deterministic host-test evidence, not a long-duration device-test receipt.
