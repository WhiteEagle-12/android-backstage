#!/usr/bin/env bash
# Provision a zero-extra-cost GCE VM (e2-small in us-central1) for Android Backstage
# using existing GCP starter/Developer Program credits, with Cloudflare Tunnel + R2 + Resend.
set -euo pipefail

PROJECT_ID="${GCP_PROJECT_ID:-android-backstage-prod}"
ZONE="${GCP_ZONE:-us-central1-a}"
INSTANCE="${GCP_INSTANCE:-backstage-relay-1}"
MACHINE_TYPE="${GCP_MACHINE_TYPE:-e2-small}"
DISK_SIZE_GB="${GCP_DISK_SIZE_GB:-30}"

cat <<EOF
=== Android Backstage GCE Provisioning ===
Project:  ${PROJECT_ID}
Zone:     ${ZONE}
Instance: ${INSTANCE} (${MACHINE_TYPE}, ${DISK_SIZE_GB} GB pd-balanced)

Make sure you have already:
  1. Created project ${PROJECT_ID} and linked it to your Ultra billing account (01322E-F549E3-3EF243).
  2. Set a GCP budget alert (e.g. \$20 / \$35 / \$40) before launching.
  3. Created a Cloudflare Tunnel for relay.android-backstage.com -> http://127.0.0.1:18421.
  4. Verified android-backstage.com in Resend (for login@android-backstage.com).
  5. (Recommended) Created a private Cloudflare R2 bucket with a 1-day expiry lifecycle rule.
EOF

read -r -p "Proceed with creating VM ${INSTANCE}? [y/N] " ans
[[ "${ans,,}" == "y" ]] || exit 0

gcloud config set project "${PROJECT_ID}"
gcloud services enable compute.googleapis.com

# No public HTTP/SSH firewall needed when using Cloudflare Tunnel + IAP SSH.
gcloud compute instances create "${INSTANCE}" \
  --zone="${ZONE}" \
  --machine-type="${MACHINE_TYPE}" \
  --boot-disk-size="${DISK_SIZE_GB}GB" \
  --boot-disk-type=pd-balanced \
  --image-family=debian-12 \
  --image-project=debian-cloud \
  --network-tier=STANDARD \
  --no-scopes \
  --metadata=startup-script='#!/usr/bin/env bash
set -euo pipefail
apt-get update -y
apt-get install -y ca-certificates curl gnupg git rsync
if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
fi
if ! command -v cloudflared >/dev/null 2>&1; then
  curl -fsSL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o /tmp/cloudflared.deb
  dpkg -i /tmp/cloudflared.deb && rm -f /tmp/cloudflared.deb
fi
id -u backstage >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin backstage
install -d -o backstage -g backstage -m 0700 /var/lib/android-backstage /var/lib/android-backstage/relay /var/lib/android-backstage/work /var/lib/android-backstage/app-releases /etc/android-backstage
'

echo "VM created. Next: gcloud compute ssh ${INSTANCE} --zone=${ZONE}, install the release under /opt/android-backstage, copy backstage.env.example -> /etc/android-backstage/env (0600), and enable the systemd units in deploy/gcp/README.md."
