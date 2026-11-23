#!/usr/bin/python3
"""Local Backstage service recovery. Runs from a systemd user timer, not an agent session."""
import concurrent.futures
import fcntl
import json
import os
from pathlib import Path
import subprocess
import time

BASE = Path.home() / '.local/state/android-backstage-supervisor'
SERVICES = {
    'work': ('android-backstage-work.service', [('http://127.0.0.1:18424/work/health', 'backstage-work')]),
    'relay': ('android-backstage-relay.service', [('http://127.0.0.1:18420/health', 'backstage-relay'), ('http://127.0.0.1:18421/health', 'backstage-relay')]),
    'tunnel': ('android-backstage-tunnel.service', [('http://127.0.0.1:18423/ready', 'tunnel')]),
}
PUBLIC = [('https://relay.android-backstage.com/work/health', 'backstage-work'), ('https://relay.android-backstage.com/health', 'backstage-relay')]
FAILURES = 3
COOLDOWN = 300
GRACE = 90


def run(argv, timeout=10):
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout, check=False)


def probe(url, expected):
    try:
        # curl uses the same verified HTTPS path as the deployed service tooling.
        r = run(['/usr/bin/curl', '--silent', '--show-error', '--fail', '--connect-timeout', '3', '--max-time', '6', '--max-filesize', '65536', '--noproxy', '*', url], 8)
        if r.returncode:
            return False
        value = json.loads(r.stdout)
        if not isinstance(value, dict):
            return False
        if expected == 'tunnel':
            count = value.get('readyConnections')
            return value.get('status') == 200 and isinstance(count, int) and not isinstance(count, bool) and count > 0
        return value.get('ok') is True and value.get('service') == expected
    except (ValueError, OSError, subprocess.TimeoutExpired):
        return False


def service_state(unit):
    r = run(['/usr/bin/systemctl', '--user', 'show', unit, '--property=ActiveState,ActiveEnterTimestampMonotonic'])
    if r.returncode:
        raise RuntimeError('Cannot read systemd service state: ' + unit)
    props = dict(line.split('=', 1) for line in r.stdout.splitlines() if '=' in line)
    since = int(props.get('ActiveEnterTimestampMonotonic', '0')) / 1_000_000
    # systemd exports CLOCK_MONOTONIC timestamps, as used by time.monotonic().
    uptime = time.monotonic()
    return props.get('ActiveState', 'unknown'), max(0, uptime - since) if since else None


def decide(previous, healthy, state, age, now):
    """Pure policy: three failures; no restart while starting/stopping or cooling down."""
    row = dict(previous)
    row['healthy'] = healthy
    row['state'] = state
    row['failures'] = 0 if healthy else row.get('failures', 0) + 1
    if healthy:
        return row, None
    if state in ('activating', 'deactivating') or age is not None and age < GRACE:
        return row, None
    if row['failures'] < FAILURES or now - row.get('lastRecovery', 0) < COOLDOWN:
        return row, None
    row['lastRecovery'] = now
    row['failures'] = 0
    return row, 'start' if state in ('inactive', 'failed') else 'restart'


def notify(message):
    command = Path.home() / '.local/bin/agent-notify'
    if command.is_file():
        try:
            r = run([str(command), 'codex', message], 15)
            if r.returncode:
                print('Phone notification failed; see the supervisor journal', flush=True)
        except (OSError, subprocess.TimeoutExpired):
            print('Phone notification unavailable', flush=True)


def save(state):
    temp = BASE / 'health.new'
    temp.write_text(json.dumps(state, indent=2) + '\n')
    temp.replace(BASE / 'health.json')


def main():
    os.umask(0o077)
    BASE.mkdir(parents=True, exist_ok=True)
    # Deployments hold this same lock across stops, backups, starts and health checks.
    with (BASE / 'maintenance.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('Planned maintenance in progress; recovery paused', flush=True)
            return
        try:
            saved = json.loads((BASE / 'health.json').read_text())
        except (FileNotFoundError, ValueError):
            saved = {}
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        if saved.get('boot') != boot:
            saved = {'boot': boot}
        now = time.time()
        urls = [item for _, checks in SERVICES.values() for item in checks] + PUBLIC
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(urls)) as pool:
            good = dict(zip((url for url, _ in urls), pool.map(lambda item: probe(*item), urls)))
        rows = saved.setdefault('services', {})
        attempts = []
        for name, (unit, checks) in SERVICES.items():
            state, age = service_state(unit)
            healthy = state == 'active' and all(good[url] for url, _ in checks)
            row, action = decide(rows.get(name, {}), healthy, state, age, now)
            rows[name] = row
            if action:
                # Persist the cooldown before issuing a restart, including if the checker dies.
                save(saved)
                r = run(['/usr/bin/systemctl', '--user', '--no-block', action, unit])
                row['lastAction'] = action
                row['actionAccepted'] = r.returncode == 0
                attempts.append(name)
                print(f'{action} {unit}: ' + ('accepted' if r.returncode == 0 else 'failed'), flush=True)
        public_ok = all(good[url] for url, _ in PUBLIC)
        saved['publicHealthy'] = public_ok
        saved['publicFailures'] = 0 if public_ok else saved.get('publicFailures', 0) + 1
        saved['checkedAt'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
        saved['checks'] = good
        # Do not restart a healthy origin because Cloudflare or the Internet is unavailable.
        if public_ok and all(row['healthy'] for row in rows.values()):
            if saved.get('outageNotified'):
                notify('Backstage is reachable again')
            saved['outageNotified'] = False
        elif saved['publicFailures'] >= FAILURES and not saved.get('outageNotified'):
            notify('Backstage is unreachable; automatic recovery is checking it')
            saved['outageNotified'] = True
        save(saved)
        print(json.dumps({'checkedAt': saved['checkedAt'], 'local': {k: v['healthy'] for k, v in rows.items()}, 'publicHealthy': public_ok, 'recovery': attempts}), flush=True)


if __name__ == '__main__':
    main()
