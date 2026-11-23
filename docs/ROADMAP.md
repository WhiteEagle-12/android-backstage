# Product direction

The strongest initial product is a private Android QA appliance: use real
phones to build, install, reproduce a bug and return evidence while their
owners keep using the physical screen. This has a concrete buyer and a
measurable outcome: a repeatable test that catches a real regression.

## Implemented foundations

- Durable asynchronous jobs, explicit cancellation and unknown outcomes.
- Encrypted resumable APK/file delivery and result ownership checks.
- Per-phone serialization, separate linked-agent identities, local revocation,
  pause and short-lived package-specific QA permissions.
- Private workflow receipts, assertions and successful-run compilation into
  reusable parameterized routines. Display IDs come from the current launch.
- Direct encrypted LAN calls (`--direct`) for private installations.
- Android lifecycle fixes, enforced lint, separate development signing,
  operator key rotation, aggregate metrics and an operational recovery guide.

The timed QA permit intentionally covers launch and diagnostic tools only.
An explicit UI workflow currently requires a full-trust link. Safely limiting
arbitrary taps to one app requires enforcing window ownership throughout the
entire interaction; tool-name filtering is not sufficient.

## Experiments worth building next

1. **Regression compiler.** Turn an exploratory agent run into a deterministic
   test with semantic selectors, expected visible state, crash assertions and
   a human-editable change review. The receipt compiler is the first version;
   autonomous assertion discovery and screenshot baseline comparison remain
   experiments.
2. **Prepare and hand off.** Let an agent prepare a task on a private display,
   show the user a preview, then hand the app to the physical screen for a
   sensitive final action. The display handoff tool already exists; a user
   preview/approval protocol and app-specific guarantees do not yet exist.
3. **Device lab with task leases.** Reserve named hardware for a test run,
   compare device-specific behavior and attach a reproducible failure bundle
   to a developer's workflow. The device lease and evidence primitives exist;
   fleet scheduling, CI provider integrations and a team console are next.
4. **Task-specific authority.** Replace a general remote-control grant with
   a visible, expiring permission for a named app and specific task. Current
   QA permits prove the restricted case; scoped arbitrary UI, user-visible
   approval and granular package install privileges need separate designs.

## Release boundary

This is an alpha, not an app-store-ready hosted service. Billing, a team
console, horizontal scaling, autonomous visual regression discovery and a
signed production update channel are not implemented. They should follow
successful real-device recovery tests and a small private QA pilot. Avoid
expanding the action surface before recovery, permissions and evidence have
been tested under interruptions on every supported phone.
