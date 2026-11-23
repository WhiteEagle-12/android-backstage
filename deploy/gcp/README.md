# Zero-Extra-Cost GCP + Cloudflare Deployment Guide

This setup moves `relay.android-backstage.com` off a personal workstation onto a dedicated GCE VM covered by existing GCP credits, while eliminating GCP egress bills for large files and APKs.

## Architecture ($0/month extra out-of-pocket)

| Component | Where it runs | Monthly cost |
| :--- | :--- | :--- |
| Static site (`android-backstage.com`) | Cloudflare Pages | **$0** (unlimited bandwidth) |
| Release APKs (`*.apk`) | GitHub Releases or Cloudflare R2 | **$0** (unlimited / $0 egress) |
| Encrypted file transfers (`/v1/files`, `/v1/uploads`) | Cloudflare R2 (10 GB free, 1-day lifecycle) | **$0** ($0 egress!) |
| Sign-in OTP emails (`login@android-backstage.com`) | Resend Free Tier (3,000/mo) | **$0** |
| Blind Relay + Hosted MCP Gateway (`relay.android-backstage.com`) | GCE `e2-small` (`us-central1`, 30 GB PD) + Cloudflare Tunnel | **~$15/mo** (covered by $300 starter credit through Feb 21, 2027, then $40/mo Developer Program credit) |

## Why GCE VM (not Cloud Run)
- Connected phones hold 24/7 long-poll sockets (`/v1/device/next`) and SSE streams (`/v1/events/stream`). Cloud Run cannot scale to zero with even one online phone, and its ephemeral multi-replica model conflicts with the single-writer local state directory.
- Using `network-tier=STANDARD` + Cloudflare Tunnel + R2 offload keeps GCE egress tiny (text/JSON MCP traffic only) and exposes **zero open ports** on the VM.

## Quick Setup
1. Run `bash deploy/gcp/setup-gce.sh` (set a GCP budget alert first).
2. Create a private Cloudflare R2 bucket with a **1-day object expiration lifecycle rule**, and copy credentials into `/etc/android-backstage/env` (mode `0600`) from `deploy/gcp/backstage.env.example`.
3. Verify `android-backstage.com` in Resend and set `BACKSTAGE_RESEND_API_KEY`.
4. Install systemd units for `android-backstage-front` (`gateway/front.mjs`), `android-backstage-work` (`gateway/server.mjs`), and `cloudflared` pointing `relay.android-backstage.com` → `http://127.0.0.1:18421`.
5. In the Cloudflare dashboard for `android-backstage.com`, enable **Always Use HTTPS**, **Minimum TLS 1.2**, **Bot Fight Mode**, and an edge rate-limit rule on `/work/authorize` and `/work/register` (30 req/min/IP).
6. Enable daily GCE disk snapshots + encrypted off-box backup via `scripts/backup-host.sh`.

## Long-Term Move (Post-Credits)
If monthly egress or VM spend ever outgrows GCP credits, lift the exact same `/opt/android-backstage` + `/var/lib/android-backstage` directory onto a **$5–$10/mo Hetzner Cloud VPS** (20 TB/mo egress included) behind the same Cloudflare Tunnel.
