# Security Policy

## Supported Versions

Only the latest `2.x` release of Android Backstage (Android app, hosted/self-hosted gateway, relay, and CLI) receives security updates.

## Reporting a Vulnerability

Please report security issues privately rather than opening a public GitHub issue:

- **Email:** `security@android-backstage.com`
- **GitHub:** Use **Security → Report a vulnerability** on `WhiteEagle-12/android-backstage`

Include affected component/version, reproduction steps, and impact. We will acknowledge reports within 48 hours and coordinate a fix before public disclosure.

## Scope & Trust Boundaries

- **Hosted MCP (`/work/mcp`):** The hosted gateway holds vault-sealed device link keys and decrypts commands/results in memory to forward between cloud assistants and your device (zero command/result history retention on disk).
- **Local E2EE Bridge (`mcp-bridge` / self-hosted relay):** End-to-end encrypted (`SPAKE2` + `AES-256-GCM`) strictly between your computer and Android device.
- **On-device Privacy Fence:** Apps added to **Blocked apps** (and Backstage's own app screens) are enforced inside the device daemon. Enabling optional **Allow command-line access** (`android_shell`) bypasses Blocked apps.
