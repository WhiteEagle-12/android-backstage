# Zero-Extra-Cost GCP + Cloudflare + Zero-Retention Deployment Guide

This setup runs `relay.android-backstage.com` on Google Cloud's **Always Free `e2-micro`** (1 GB RAM, 30 GB `pd-standard`, `$0/month` forever) with zero open ports, zero plaintext device keys on disk, and real zero data retention.

## Architecture ($0/month forever on Always Free tier)

| Component | Where it runs | Monthly cost |
| :--- | :--- | :--- |
| Static site (`android-backstage.com`) | Cloudflare Pages | **$0** (unlimited bandwidth) |
| Release APKs (`*.apk`) | GitHub Releases (`WhiteEagle-12/android-backstage`) | **$0** (unlimited) |
| Encrypted file transfers (`/v1/files`, `/v1/uploads`) | Cloudflare R2 (10 GB free, 1-day lifecycle) | **$0** ($0 egress) |
| Sign-in OTP emails (`login@android-backstage.com`) | Resend Free Tier (3,000/mo) | **$0** |
| Blind Relay + Hosted MCP Gateway (`relay.android-backstage.com`) | GCE Always Free `e2-micro` (`us-central1`, 1 GB RAM, 30 GB `pd-standard`) + Cloudflare Tunnel | **$0** (or resize to `e2-small` ~$12/mo when traffic grows) |

## Memory Budget on `e2-micro` (1 GB RAM)
- Linux OS + systemd: ~150 MB
- `gateway/front.mjs` + `relay/server.mjs`: ~50 MB RSS (cap with `MemoryMax=256M`)
- `gateway/server.mjs`: ~60 MB RSS (cap with `MemoryMax=256M`)
- `cloudflared`: ~35 MB RSS (cap with `MemoryMax=128M`)
- Capped RAM-only `tmpfs`: `128M` (`/run/android-backstage/artifacts`) + `128M` (`/run/android-backstage/relay-files`)
- Compressed in-RAM `zram` swap (`zramswap`): 512 MB safety cushion without writing unencrypted swap to disk

## Zero Data Retention & Vault Encryption
- `BACKSTAGE_ZERO_RETENTION=1` (default ON):
  - **No `calls.jsonl`** written to disk.
  - **Request deduplication journal runs in RAM only** (5-minute TTL; zero tool results/screenshots on disk).
  - **Transient artifacts live on capped `tmpfs`** (5-minute TTL, burn-after-read on pulled phone files, zero-overwritten before unlink).
- `RELAY_ZERO_RETENTION=1` (default ON):
  - **Job queue & operational dashboard run in RAM only** (5-minute job TTL; zero job files on disk).
  - **Temporary relay files live on R2 or capped `tmpfs`** (5-minute TTL, single-use, zero-overwritten on delete).
- `BACKSTAGE_VAULT_KEY_FILE=/etc/android-backstage/vault.key`:
  - Every `phones/<deviceId>.json` seals `linkKey` and `apiKey` with **AES-256-GCM** bound to `deviceId` via AAD. Keep `vault.key` outside `/var/lib/android-backstage` (or load at boot from GCP Secret Manager) so disk dumps or backups contain zero usable phone keys.

## Quick Setup
1. Run `bash deploy/gcp/setup-gce.sh` (defaults to Always Free `e2-micro`; pass `GCP_MACHINE_TYPE=e2-small GCP_DISK_TYPE=pd-balanced` to scale up).
2. Generate the vault key on the VM:
   ```bash
   sudo openssl rand -hex 32 | sudo tee /etc/android-backstage/vault.key >/dev/null
   sudo chown backstage:backstage /etc/android-backstage/vault.key
   sudo chmod 0600 /etc/android-backstage/vault.key
   ```
3. Copy `deploy/gcp/backstage.env.example` to `/etc/android-backstage/env` (mode `0600`), fill in Resend + R2 + `RELAY_ADMIN_TOKEN`, and start `android-backstage-front`, `android-backstage-work`, and `cloudflared` under systemd with `MemoryMax=256M`.
4. In Cloudflare for `android-backstage.com`, enable **Always Use HTTPS**, **Minimum TLS 1.2**, **Bot Fight Mode**, and an edge rate-limit rule on `/work/authorize` and `/work/register` (30 req/min/IP).

## Scaling Past `e2-micro`
- **Step 1 (hundreds of active users):** Stop VM, resize in-place to `e2-small` (2 GB RAM, ~$12/mo — covered by GCP credits).
- **Step 2 (high egress or post-credits):** Move `/opt/android-backstage` + `/var/lib/android-backstage` onto a **$5–$10/mo Hetzner Cloud VPS** (20 TB/mo egress included) behind the same Cloudflare Tunnel.
- **Step 3 (hardware-attested Pro/Enterprise tier):** Deploy `gateway/` inside **GCP Confidential Space (AMD SEV-SNP)** with KMS workload-identity sealing so even the host operator cannot inspect live RAM.
