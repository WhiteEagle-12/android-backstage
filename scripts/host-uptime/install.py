#!/usr/bin/python3
"""Install persistent Backstage uptime settings on this Linux host. No reboot or network disconnect."""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess

HOME_DIR = Path.home()
HERE = Path(__file__).resolve().parent
RUNTIME = HOME_DIR / '.local/share/android-backstage/host-uptime'
UNITS = HOME_DIR / '.config/systemd/user'
STATE = HOME_DIR / '.local/state/android-backstage-supervisor'
BACKUP = STATE / ('before-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
SERVICES = ['android-backstage-work', 'android-backstage-relay', 'android-backstage-tunnel']
TARGETS = ['sleep.target', 'suspend.target', 'hibernate.target', 'hybrid-sleep.target', 'suspend-then-hibernate.target']


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout.strip()


def write(path, text):
    path = Path(path)
    if path.exists() or path.is_symlink():
        dest = BACKUP / str(path).lstrip('/')
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest, follow_symlinks=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + '.new')
    temp.write_text(text)
    temp.replace(path)


os.umask(0o077)
run('sudo', '-n', 'true')
BACKUP.mkdir(parents=True)
RUNTIME.mkdir(parents=True, exist_ok=True)
# Save previous settings before making any changes. Never read a Wi-Fi password.
wifi = run('nmcli', '-g', 'GENERAL.CON-UUID', 'device', 'show', 'wlan0')
wifi_fields = ['connection.autoconnect', 'connection.autoconnect-retries', '802-11-wireless.powersave']
receipt = {'backup': str(BACKUP), 'wifiProfile': wifi,
           'wifiBefore': {key: run('nmcli', '-g', key, 'connection', 'show', wifi) for key in wifi_fields},
           'lingerBefore': run('loginctl', 'show-user', str(os.getuid()), '-p', 'Linger', '--value'),
           'sleepTargetsBefore': {target: subprocess.run(['systemctl', 'is-enabled', target], capture_output=True, text=True).stdout.strip() for target in TARGETS}}
(BACKUP / 'settings.json').write_text(json.dumps(receipt, indent=2) + '\n')
write(RUNTIME / 'health.py', (HERE / 'health.py').read_text())
for name in SERVICES:
    write(UNITS / (name + '.service.d/uptime.conf'), '''[Unit]
StartLimitIntervalSec=0

[Service]
Restart=always
RestartSec=5
TimeoutStopSec=15
KillMode=control-group
OOMPolicy=stop
''')
write(UNITS / 'android-backstage-health.service', f'''[Unit]
Description=Check and recover Android Backstage services
StartLimitIntervalSec=0

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 {RUNTIME}/health.py
TimeoutStartSec=45
Restart=on-failure
RestartSec=15
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
Environment=PATH=/usr/local/bin:/usr/bin:/bin:{HOME_DIR}/.local/bin
''')
write(UNITS / 'android-backstage-health.timer', '''[Unit]
Description=Keep Android Backstage reachable

[Timer]
OnBootSec=45
OnUnitInactiveSec=30
AccuracySec=1
Unit=android-backstage-health.service

[Install]
WantedBy=timers.target
''')
sleep = Path('/etc/systemd/sleep.conf.d/90-android-backstage-awake.conf')
if sleep.exists():
    (BACKUP / 'sleep.conf').write_text(run('sudo', '-n', 'cat', str(sleep)))
local_sleep = BACKUP / 'new-sleep.conf'
local_sleep.write_text('''[Sleep]
AllowSuspend=no
AllowHibernation=no
AllowHybridSleep=no
AllowSuspendThenHibernate=no
''')
run('sudo', '-n', 'install', '-D', '-m', '0644', str(local_sleep), str(sleep))
run('sudo', '-n', 'systemctl', 'mask', *TARGETS)
run('sudo', '-n', 'systemctl', 'daemon-reload')
run('sudo', '-n', 'loginctl', 'enable-linger', str(os.getuid()))
run('sudo', '-n', 'nmcli', 'connection', 'modify', wifi, 'connection.autoconnect', 'yes', 'connection.autoconnect-retries', '0', '802-11-wireless.powersave', '2')
run('sudo', '-n', 'iw', 'dev', 'wlan0', 'set', 'power_save', 'off')
run('systemctl', '--user', 'daemon-reload')
run('systemctl', '--user', 'enable', '--now', *[name + '.service' for name in SERVICES], 'android-backstage-health.timer')
run('systemctl', '--user', 'start', 'android-backstage-health.service')
receipt['installed'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
(STATE / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps({'installed': True, 'backup': str(BACKUP), 'runtime': str(RUNTIME)}))
