#!/usr/bin/python3
"""Controlled work-service fault injection; restores the service even when an assertion fails."""
import json
from pathlib import Path
import subprocess
import time
import urllib.request

UNIT = 'android-backstage-work.service'
STATE = Path.home() / '.local/state/android-backstage-supervisor'

def ctl(*args):
    return subprocess.run(['systemctl', '--user', *args], check=True, capture_output=True, text=True, timeout=50).stdout.strip()

def pid():
    return int(ctl('show', UNIT, '-p', 'MainPID', '--value'))

def healthy():
    try:
        with urllib.request.urlopen('http://127.0.0.1:18424/work/health', timeout=1) as r:
            return json.load(r).get('service') == 'backstage-work'
    except Exception:
        return False

receipt = {}
try:
    assert healthy(), 'service must be healthy before test'
    original = pid()
    started = time.monotonic()
    ctl('kill', '--kill-whom=main', '--signal=SIGSTOP', UNIT)
    assert not healthy(), 'SIGSTOP must make the service unresponsive'
    print('Stuck process confirmed. Running the installed health checker.', flush=True)
    # Exercise the installed service and persisted failure counter, rather than a mock.
    for _ in range(4):
        ctl('start', 'android-backstage-health.service')
        state = json.loads((STATE / 'health.json').read_text())
        if state['services']['work'].get('lastAction') == 'restart':
            break
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        if pid() not in (0, original) and healthy():
            break
        time.sleep(1)
    else:
        raise AssertionError('health checker did not replace the stuck process')
    receipt['hungProcess'] = {'oldPID': original, 'newPID': pid(), 'recoveredSeconds': round(time.monotonic()-started, 2)}
    print('PASS: health checker recovered the stuck service.', flush=True)
    original = pid()
    started = time.monotonic()
    ctl('kill', '--kill-whom=main', '--signal=SIGTERM', UNIT)
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if pid() not in (0, original) and healthy():
            break
        time.sleep(0.5)
    else:
        raise AssertionError('systemd did not restart the exited service')
    receipt['unexpectedExit'] = {'oldPID': original, 'newPID': pid(), 'recoveredSeconds': round(time.monotonic()-started, 2)}
    print('PASS: process exit recovered automatically.', flush=True)
finally:
    if not healthy():
        subprocess.run(['systemctl', '--user', 'kill', '--kill-whom=main', '--signal=SIGCONT', UNIT], capture_output=True)
        subprocess.run(['systemctl', '--user', 'restart', UNIT], check=True, timeout=50)
    ctl('start', 'android-backstage-health.service')
    receipt['finalHealthy'] = healthy()
    (STATE / 'recovery-test.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt), flush=True)
