#!/usr/bin/env bash
# Encrypted off-machine backup of the Backstage state on this host.
#
# Copies the gateway state (owners, OAuth, device links, grants) and the relay
# data directory into one tarball, encrypts it with a passphrase that lives only
# on this machine, and writes it to BACKSTAGE_BACKUP_DEST (a mounted drive, an
# SSH/rsync target, or any directory). Nothing leaves the machine unencrypted.
#
#   scripts/backup-host.sh                 # run once
#   scripts/backup-host.sh --install-timer # daily systemd user timer
#
# Configuration: ~/.config/android-backstage/backup.env
#   BACKSTAGE_BACKUP_DEST=/mnt/usb/backstage        # or user@host:/srv/backstage (rsync over ssh)
#   BACKSTAGE_BACKUP_KEEP=14                         # copies to keep at a directory destination
#
# Restoring: openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:~/.config/android-backstage/backup.key -in <file> | tar -xz -C <dir>
# Keep a copy of backup.key somewhere safe and separate from the backups; without it they are unreadable.
# A restored copy is a snapshot: device links and grants revoked after the snapshot must not be silently revived.
set -euo pipefail

CONF_DIR="$HOME/.config/android-backstage"
ENV_FILE="$CONF_DIR/backup.env"
KEY_FILE="$CONF_DIR/backup.key"
WORK_DIR="${BACKSTAGE_WORK_STATE:-$CONF_DIR/work}"
RELAY_DIR="${BACKSTAGE_RELAY_DATA:-$HOME/.local/share/android-backstage/data}"
[ -f "$ENV_FILE" ] && . "$ENV_FILE"
KEEP="${BACKSTAGE_BACKUP_KEEP:-14}"

if [ "${1:-}" = "--install-timer" ]; then
  mkdir -p "$HOME/.config/systemd/user"
  cat >"$HOME/.config/systemd/user/android-backstage-backup.service" <<EOF
[Unit]
Description=Encrypted off-machine backup of Android Backstage state

[Service]
Type=oneshot
ExecStart=/usr/bin/env bash $(realpath "$0")
EOF
  cat >"$HOME/.config/systemd/user/android-backstage-backup.timer" <<EOF
[Unit]
Description=Daily Android Backstage backup

[Timer]
OnCalendar=daily
RandomizedDelaySec=30m
Persistent=true

[Install]
WantedBy=timers.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable --now android-backstage-backup.timer
  echo "Installed daily timer. Set BACKSTAGE_BACKUP_DEST in $ENV_FILE."
  exit 0
fi

if [ -z "${BACKSTAGE_BACKUP_DEST:-}" ]; then
  echo "BACKSTAGE_BACKUP_DEST is not set. Create $ENV_FILE with the destination first." >&2
  exit 2
fi

umask 077
mkdir -p "$CONF_DIR"
if [ ! -f "$KEY_FILE" ]; then
  openssl rand -base64 48 >"$KEY_FILE"
  echo "Created $KEY_FILE. Store a copy of it away from the backups." >&2
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="backstage-$STAMP.tar.gz.enc"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Copy first so the archive is a consistent point in time even while services write.
mkdir -p "$TMP/snap"
[ -d "$WORK_DIR" ] && rsync -a --exclude 'artifacts/' "$WORK_DIR/" "$TMP/snap/work/"
[ -d "$RELAY_DIR" ] && rsync -a --exclude 'files/' --exclude 'uploads/' "$RELAY_DIR/" "$TMP/snap/relay/"
tar -C "$TMP/snap" -czf - . | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$KEY_FILE" -out "$TMP/$NAME"
sha256sum "$TMP/$NAME" | cut -d' ' -f1 >"$TMP/$NAME.sha256"

case "$BACKSTAGE_BACKUP_DEST" in
  *:*)
    rsync -a "$TMP/$NAME" "$TMP/$NAME.sha256" "$BACKSTAGE_BACKUP_DEST/" ;;
  *)
    mkdir -p "$BACKSTAGE_BACKUP_DEST"
    cp "$TMP/$NAME" "$TMP/$NAME.sha256" "$BACKSTAGE_BACKUP_DEST/"
    ls -1t "$BACKSTAGE_BACKUP_DEST"/backstage-*.tar.gz.enc 2>/dev/null | tail -n +"$((KEEP+1))" | while read -r old; do rm -f "$old" "$old.sha256"; done ;;
esac
echo "Backed up $(stat -c %s "$TMP/$NAME") bytes to $BACKSTAGE_BACKUP_DEST/$NAME"
