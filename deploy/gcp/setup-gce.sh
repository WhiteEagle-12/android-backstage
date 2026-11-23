#!/usr/bin/env bash
# Provision a $0/month Always Free GCE VM (e2-micro in us-central1, 1 GB RAM, 30 GB pd-standard)
# or scaled e2-small for Android Backstage with Cloudflare Tunnel + R2 + Resend + zero-retention tmpfs.
#
# Usage:
#   bash deploy/gcp/setup-gce.sh                    # Always Free e2-micro ($0/mo forever)
#   GCP_MACHINE_TYPE=e2-small bash deploy/gcp/setup-gce.sh  # Scale up to 2 GB RAM (~$12/mo)
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:-android-backstage-prod}"
ZONE="${GCP_ZONE:-us-central1-a}"
INSTANCE="${GCP_INSTANCE:-backstage-relay-1}"
MACHINE_TYPE="${GCP_MACHINE_TYPE:-e2-micro}"
DISK_SIZE_GB="${GCP_DISK_SIZE_GB:-30}"
DISK_TYPE="${GCP_DISK_TYPE:-pd-standard}"

cat <<EOF
=== Android Backstage GCE Provisioning ===
Project:  ${PROJECT_ID}
Zone:     ${ZONE}
Instance: ${INSTANCE} (${MACHINE_TYPE}, ${DISK_SIZE_GB} GB ${DISK_TYPE})

Make sure you have already:
  1. Created project ${PROJECT_ID} and linked it to your GCP billing account.
  2. Set a GCP budget alert (e.g. \$5 / \$20 / \$35) before launching.
  3. Created a Cloudflare Tunnel for relay.android-backstage.com -> http://127.0.0.1:18421.
  4. Verified android-backstage.com in Resend (for login@android-backstage.com).
  5. (Recommended) Created a private Cloudflare R2 bucket with a 1-day expiry lifecycle rule.
EOF

read -r -p "Proceed with creating VM ${INSTANCE}? [y/N] " ans
[[ "${ans,,}" == "y" ]] || exit 0

gcloud config set project "${PROJECT_ID}"
gcloud services enable compute.googleapis.com

STARTUP_FILE="$(mktemp)"
trap 'rm -f "$STARTUP_FILE"' EXIT
cat >"$STARTUP_FILE" <<'STARTUP'
#!/usr/bin/env bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y ca-certificates curl gnupg git rsync zram-tools
if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
if ! command -v cloudflared >/dev/null 2>&1; then
  curl -fsSL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o /tmp/cloudflared.deb
  dpkg -i /tmp/cloudflared.deb && rm -f /tmp/cloudflared.deb
fi
# Compressed in-RAM zram swap (no unencrypted swap written to disk; gives 1 GB e2-micro safe headroom).
cat >/etc/default/zramswap <<ZRAM
ALGO=zstd
PERCENT=50
PRIORITY=100
ZRAM
systemctl enable --now zramswap || true

id -u backstage >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin backstage
install -d -o backstage -g backstage -m 0700 \
  /var/lib/android-backstage \
  /var/lib/android-backstage/relay \
  /var/lib/android-backstage/work \
  /var/lib/android-backstage/app-releases \
  /etc/android-backstage

# Capped RAM-only tmpfs mounts for transient artifacts and relay files (vanish on reboot, never hit SSD).
install -d -o backstage -g backstage -m 0700 /run/android-backstage /run/android-backstage/artifacts /run/android-backstage/relay-files
grep -q "/run/android-backstage/artifacts" /etc/fstab || cat >>/etc/fstab <<FSTAB
tmpfs /run/android-backstage/artifacts tmpfs rw,nosuid,nodev,noexec,size=128M,mode=0700,uid=backstage,gid=backstage 0 0
tmpfs /run/android-backstage/relay-files tmpfs rw,nosuid,nodev,noexec,size=128M,mode=0700,uid=backstage,gid=backstage 0 0
FSTAB
mount -a || true
STARTUP

# No public HTTP/SSH firewall needed when using Cloudflare Tunnel + IAP SSH.
# In us-central1 / us-west1 / us-east1, 1x e2-micro + 30 GB pd-standard + STANDARD tier is Always Free ($0/mo).
gcloud compute instances create "${INSTANCE}" \
  --zone="${ZONE}" \
  --machine-type="${MACHINE_TYPE}" \
  --boot-disk-size="${DISK_SIZE_GB}GB" \
  --boot-disk-type="${DISK_TYPE}" \
  --image-family=debian-12 \
  --image-project=debian-cloud \
  --network-tier=STANDARD \
  --no-scopes \
  --metadata=enable-oslogin=TRUE \
  --metadata-from-file="startup-script=${STARTUP_FILE}"

echo "VM created. Next: gcloud compute ssh ${INSTANCE} --zone=${ZONE}, install the release under /opt/android-backstage, generate /etc/android-backstage/vault.key (mode 0600), copy backstage.env.example -> /etc/android-backstage/env (0600), and enable the systemd units in deploy/gcp/README.md."
