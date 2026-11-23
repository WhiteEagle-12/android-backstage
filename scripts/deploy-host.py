#!/usr/bin/env python3
"""Install a tested commit on this machine, preserving private persistent state."""
import fcntl, io, json, os, pathlib, shutil, subprocess, tarfile, time, urllib.request
os.umask(0o077)
repo=pathlib.Path(__file__).resolve().parents[1]
base=pathlib.Path.home()/'.local/share/android-backstage'
units=pathlib.Path.home()/'.config/systemd/user'
node=base/'node-v22.23.2-linux-x64/bin/node'

def run(*args, **kwargs):
    return subprocess.run(args,check=True,**kwargs)

def system(*args):
    run('systemctl','--user',*args)

if run('git','status','--porcelain',cwd=repo,capture_output=True,text=True).stdout.strip():
    raise SystemExit('Commit the validated source before deploying.')
# Hold the same lock as the uptime supervisor until deployment or rollback finishes.
maintenance_dir=pathlib.Path.home()/'.local/state/android-backstage-supervisor'
maintenance_dir.mkdir(parents=True,exist_ok=True)
maintenance_lock=(maintenance_dir/'maintenance.lock').open('a')
fcntl.flock(maintenance_lock,fcntl.LOCK_EX)
sha=run('git','rev-parse','--verify','HEAD',cwd=repo,capture_output=True,text=True).stdout.strip()
release=base/'releases'/sha
if release.exists():
    raise SystemExit('Release already exists; inspect its deployment receipt before retrying.')
release.mkdir(parents=True)
archive=run('git','archive','HEAD',cwd=repo,capture_output=True).stdout
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:tar.extractall(release,filter='data')
env={**os.environ,'PATH':str(node.parent)+os.pathsep+os.environ['PATH']}
run(str(node.parent/'npm'),'ci','--omit=dev','--ignore-scripts',cwd=release/'gateway',env=env)
receipt=base/'rollbacks'/sha
receipt.mkdir(parents=True)
services=['android-backstage-work','android-backstage-relay']
old={}
for service in services:
    drop=units/(service+'.service.d')/'connection-release.conf'
    old[service]=drop.read_text()
    (receipt/(service+'.conf')).write_text(old[service])
backup=base/'backups'/('before-'+sha+'.tar.gz')
backup.parent.mkdir(parents=True,exist_ok=True)
# Stop writers while taking a consistent snapshot. Tunnel remains available.
system('stop',*services)
try:
    with tarfile.open(backup,'w:gz') as tar:
        for source,name in [(pathlib.Path.home()/'.config/android-backstage/work','work'),(base/'data','relay-data')]:
            if source.exists():tar.add(source,arcname=name)
    for service,entry in [('android-backstage-work','gateway/server.mjs'),('android-backstage-relay','gateway/front.mjs')]:
        drop=units/(service+'.service.d')/'connection-release.conf'
        text='[Service]\nWorkingDirectory='+str(release)+'\nExecStart=\nExecStart='+str(node)+' '+str(release/entry)+'\n'
        if service.endswith('work'):text+='Environment=BACKSTAGE_RELAY_URL=https://relay.android-backstage.com\n'
        temp=drop.with_suffix('.new');temp.write_text(text);temp.replace(drop)
    system('daemon-reload');system('start',*services)
    for attempt in range(30):
        try:
            with urllib.request.urlopen('http://127.0.0.1:18421/work/health',timeout=2) as response:
                health=json.load(response)
            if health.get('ok'):break
        except Exception:
            if attempt==29:raise
            time.sleep(1)
    else:raise RuntimeError('Health check failed')
    with urllib.request.urlopen('http://127.0.0.1:18421/work/passkeys.js',timeout=5) as response:
        if b'SimpleWebAuthnBrowser' not in response.read():raise RuntimeError('Passkey browser bundle unavailable')
except BaseException:
    system('stop',*services)
    for service,text in old.items():(units/(service+'.service.d')/'connection-release.conf').write_text(text)
    system('daemon-reload');system('start',*services)
    raise
(receipt/'receipt.json').write_text(json.dumps({'commit':sha,'release':str(release),'backup':str(backup),'health':health},indent=2)+'\n')
print(json.dumps({'deployed':sha,'version':health['version'],'statePreserved':True,'backup':str(backup)}))
