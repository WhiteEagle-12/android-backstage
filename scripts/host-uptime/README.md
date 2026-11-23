# Backstage host uptime

Installed on a Linux host for the Backstage service user. The runtime does not depend on an open terminal, Codex, a chat, or the source checkout.

## Active settings

- User lingering is enabled; the user service manager starts at boot and survives logout.
- The work server, relay/front server, and Cloudflare tunnel are enabled. Each uses `Restart=always`, a five-second restart delay, no start-rate lockout, and a 15-second stop timeout. An OOM event stops the affected service group so systemd can restart it.
- The installed health checker runs every 30 seconds. It checks the work endpoint, relay and front endpoints, tunnel readiness, and both public health URLs. Three consecutive failures trigger recovery of only the affected local service. New services get 90 seconds to start; automatic recovery has a five-minute cooldown per service.
- Public failures do not restart healthy local services. Internet or Cloudflare outages cannot be repaired by restarting a healthy origin. An outage alert is sent after three failed public checks, once per outage, followed by a recovery alert when healthy again.
- The timer and checker are systemd units. The checker itself restarts after failure. Health and recovery state survive process restarts; counters reset across a computer reboot.
- Sleep, suspend, hibernate, hybrid sleep, and suspend-then-hibernate targets are masked. `/etc/systemd/sleep.conf.d/90-android-backstage-awake.conf` also disables the supported sleep modes. Screen blanking and locking remain available.
- The current Wi-Fi profile reconnects without an attempt limit, can connect before login, and has power saving disabled. The active adapter also has power saving disabled now. No Wi-Fi secret was read or changed.

This keeps the desktop awake and uses more electricity. It cannot keep a local server online through a loss of power, Internet, failed hardware, an intentional shutdown, or an entire OS lockup. No hardware watchdog is exposed on this computer. Automatic power-on after a power outage was not changed in firmware. No reboot or logout was performed during setup.

## Paths

Runtime: `~/.local/share/android-backstage/host-uptime/health.py`

State and verification: `~/.local/state/android-backstage-supervisor/`

Units: `~/.config/systemd/user/android-backstage-health.{service,timer}`

Service overrides: `~/.config/systemd/user/android-backstage-{work,relay,tunnel}.service.d/uptime.conf`

Prior settings and replaced files: path recorded in `~/.local/state/android-backstage-supervisor/installation.json`. Backups have mode 0700/0600 and contain no Wi-Fi password.

## Inspect

```bash
systemctl --user status android-backstage-work android-backstage-relay android-backstage-tunnel android-backstage-health.timer
journalctl --user -u android-backstage-health.service -n 20
cat ~/.local/state/android-backstage-supervisor/health.json
cat ~/.local/state/android-backstage-supervisor/recovery-test.json
```

## Maintenance

The current `scripts/deploy-host.py` holds the same advisory file lock as the health checker across stop, backup, start, and rollback. This prevents the checker from restarting a service midway through an update. The OS releases the lock if the deployment process exits or crashes; no stale maintenance flag can disable recovery indefinitely.

For other short maintenance commands, hold `~/.local/state/android-backstage-supervisor/maintenance.lock` with `flock`. For an intentional extended shutdown:

```bash
systemctl --user stop android-backstage-health.timer android-backstage-health.service
systemctl --user stop android-backstage-tunnel android-backstage-work android-backstage-relay
```

Resume:

```bash
systemctl --user start android-backstage-work android-backstage-relay android-backstage-tunnel android-backstage-health.timer
```

To remove this uptime policy, disable the health timer, remove only the three `uptime.conf` drop-ins installed here, and reload the user service manager. Restore any prior files from the installation backup. To allow sleep again, remove `/etc/systemd/sleep.conf.d/90-android-backstage-awake.conf` and unmask the five sleep targets, respecting any masks recorded as preexisting in `settings.json`. Restore the saved Wi-Fi profile fields if desired. Lingering was already enabled before this change and should remain enabled.

## Verification

`python3 scripts/host-uptime/test_health.py`: 10 passing tests covering consecutive failures, successful checks resetting counts, startup grace, cooldown, inactive-service recovery, ongoing start/stop transitions, malformed health responses, tunnel readiness, and the maintenance lock.

The installed systemd checker recovered a deliberately frozen work process. An unexpected SIGTERM exit restarted automatically in 6.1 seconds. The freeze test manually triggered checker runs to avoid waiting through three full timer intervals; its 20.12-second test duration is not a promise of normal detection time. The normal timer is 30 seconds with three failed samples required. The public endpoint, relay, front, and tunnel readiness were healthy afterwards. `CanSuspend` and `CanHibernate` both returned `no`; all five sleep targets were masked. Wi-Fi showed power saving off and unlimited reconnection attempts.

References: [systemd restart behavior](https://github.com/systemd/systemd/blob/main/man/systemd.service.xml), [systemd sleep settings](https://github.com/systemd/systemd/blob/main/src/shared/sleep-config.c), [Cloudflare tunnel monitoring](https://developers.cloudflare.com/tunnel/monitoring/).
