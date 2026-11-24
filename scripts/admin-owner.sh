#!/usr/bin/env bash
# 1-command production owner administration / abuse kill-switch over IAP SSH to VM 2.
# Usage:
#   ./scripts/admin-owner.sh list
#   ./scripts/admin-owner.sh inspect <email|ownerId>
#   ./scripts/admin-owner.sh revoke-grants <email|ownerId>
#   ./scripts/admin-owner.sh delete <email|ownerId> --yes
set -euo pipefail

PROJECT="${BACKSTAGE_WORK_PROJECT:-android-backstage-node2}"
ACCOUNT="${BACKSTAGE_WORK_ACCOUNT:-gavin.pate.mt@gmail.com}"
ZONE="${BACKSTAGE_WORK_ZONE:-us-central1-a}"
INSTANCE="${BACKSTAGE_WORK_INSTANCE:-backstage-gateway-1}"

cmd="${1:-}"
ref="${2:-}"
flag="${3:-}"

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/admin-owner.sh list
  scripts/admin-owner.sh inspect <email|ownerId>
  scripts/admin-owner.sh revoke-grants <email|ownerId>
  scripts/admin-owner.sh delete <email|ownerId> --yes
EOF
  exit 2
}

case "$cmd" in
  list)
    method="GET"
    endpoint="/work/internal/admin/owners"
    ;;
  inspect)
    [[ -n "$ref" ]] || usage
    method="GET"
    endpoint="/work/internal/admin/owner/__REF__"
    ;;
  revoke-grants)
    [[ -n "$ref" ]] || usage
    method="POST"
    endpoint="/work/internal/admin/owner/__REF__/revoke-grants"
    ;;
  delete)
    [[ -n "$ref" && "$flag" == "--yes" ]] || { echo "Refusing to delete without --yes." >&2; usage; }
    method="POST"
    endpoint="/work/internal/admin/owner/__REF__/delete"
    ;;
  *)
    usage
    ;;
esac

gcloud compute ssh "$INSTANCE" \
  --project="$PROJECT" \
  --account="$ACCOUNT" \
  --zone="$ZONE" \
  --tunnel-through-iap \
  --command="sudo BACKSTAGE_ADMIN_METHOD='$method' BACKSTAGE_ADMIN_ENDPOINT='$endpoint' BACKSTAGE_ADMIN_REF='$ref' node -e '
const fs = require(\"node:fs\");
const env = Object.fromEntries(fs.readFileSync(\"/etc/android-backstage/env\",\"utf8\").split(/\r?\n/).filter(l=>l&&!l.startsWith(\"#\")&&l.includes(\"=\")).map(l=>{const i=l.indexOf(\"=\");return [l.slice(0,i).trim(),l.slice(i+1).trim()];}));
const token = env.RELAY_INTERNAL_TOKEN || env.RELAY_ADMIN_TOKEN;
if (!token) { console.error(\"Missing RELAY_INTERNAL_TOKEN in /etc/android-backstage/env\"); process.exit(1); }
const port = Number(env.PORT || 18424);
const method = process.env.BACKSTAGE_ADMIN_METHOD;
const ref = encodeURIComponent(process.env.BACKSTAGE_ADMIN_REF || \"\");
const endpoint = process.env.BACKSTAGE_ADMIN_ENDPOINT.replace(\"__REF__\", ref);
fetch(\"http://127.0.0.1:\" + port + endpoint, {
  method,
  headers: { \"x-backstage-internal-key\": token, \"content-type\": \"application/json\" }
}).then(async r => {
  const txt = await r.text();
  try { console.log(JSON.stringify(JSON.parse(txt), null, 2)); } catch { console.log(txt); }
  if (!r.ok) process.exit(1);
});
'"
