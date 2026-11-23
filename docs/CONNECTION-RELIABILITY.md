# Connection reliability — 2.0.0-dev.26 and dev.27

Investigated on the RedMagic NX809J (Android 16) on October 16, 2026.

## Why the phone asked for approval again and again

The prompt was Android's own **Allow wireless debugging on this network?** dialog. Android stores **Always allow** per access point (BSSID), not per network name. On multi-AP Wi-Fi networks (home mesh, apartment, or campus networks with many access points), every access point the phone had not met before asked again.

Android also turns Wireless debugging off whenever the phone moves to another access point. Backstage turned it back on whenever the shell daemon was down, which brought up the dialog on any untrusted access point. A separate high-priority notification ("Wireless debugging is off… Turn it on") appeared on every Wi-Fi change, even while everything worked.

## Why the phone went offline

The shell daemon runs inside adbd's process group, so init kills it whenever adbd stops or restarts. Relaunching it needs an ADB connection: Wireless debugging, or adbd's TCP port when something left adbd in TCP mode. On October 16 the daemon's relay polls ended at 09:51 while Wi-Fi was flapping onto an untrusted access point. No daemon ran again until adbd started at 11:07, after the phone reached a trusted access point. Each launch also truncated the daemon log, so earlier deaths left no record.

Turning Wireless debugging off does not stop adbd while USB debugging is on; this was tested live. Away from Wi-Fi, adbd was stopped three times on October 16 (09:51, 13:49 and 15:45) with no cable attached. ZTE's `UsbDeviceManager` (decompiled from the phone's `services.jar`) explains it. On every USB function change, the legacy USB handler first sets `sys.usb.config=none`, and init's `init.usb.configfs.rc` then runs `stop adbd`. ZTE skips this only while Wireless debugging is on with a paired device (`shouldSetUsbConfigNone()`). The phone's Default USB configuration was USB tethering (`screen_unlocked_functions=RNDIS`), so even with no cable, every unlock switched USB to RNDIS and every lock switched it back. At home, Wireless debugging was on and the switch was harmless. Away from Wi-Fi, the first lock or unlock stopped adbd and the daemon with it. adbd was seen starting again at a later unlock while the phone was still on cellular.

**Fix (phone setting):** Default USB configuration is now **No data transfer** (`svc usb setScreenUnlockedFunctions` with no function). Lock and unlock no longer change USB functions, so nothing stops adbd. USB tethering is still available from the USB notification when a cable is plugged in. To restore the old default, choose Developer options → Default USB configuration → USB tethering, or run `svc usb setScreenUnlockedFunctions rndis`.

## What changed

Phone daemon:
- **Wi-Fi trust keeper.** The shell daemon holds `MANAGE_DEBUGGING`. When Android turns Wireless debugging off after a roam on one of the owner's networks, the daemon calls `IAdbManager.allowWirelessDebugging(true, bssid)`, which Android's own **Always allow** button calls. Android records the access point and turns Wireless debugging back on with no dialog. Nearby access points of those networks (seen in the last hour) are approved in advance the same way. Where Android already trusts the access point, the daemon only switches the setting back on, because each approval call adds another entry to Android's list. Eligible networks are saved networks protected by a password or enterprise sign-in, and networks the owner approved by hand. Open networks are never approved automatically. Wireless debugging still requires a paired key, so this opens no access for anyone else.
- **Lifecycle log.** `/data/local/tmp/backstage/lifecycle.log` records every daemon start, together with the previous daemon's last heartbeat and the likely cause of its stop: a reboot, adbd stopping, a crash, or a deliberate stop. It also records USB property changes, Wi-Fi and network changes, and each approval. `daemon.log` now appends and rotates (three older files) instead of being truncated on every launch.
- **Network-aware reconnect.** A network change ends the relay backoff at once and re-announces the phone's addresses.
- **App watchdog.** If the app's process disappears, the daemon restarts its supervision service.
- **Downloads** resume after drops and stop only when stalled (under 16 kB/s for 45 s) or near the call deadline. Transfer tools now have 600 s instead of 200 s.
- `/health` from other devices returns only identity and liveness.

App supervisor:
- It turns Wireless debugging on by itself only where Android already trusts the current access point. Otherwise it waits for an approved access point, or for the owner to tap the quiet **Phone access is offline** notification. Android's dialog can then appear only in answer to that tap.
- It holds the daemon's `/watch` stream, so a daemon death starts recovery within about a second. Network changes also trigger an immediate check.
- `supervisor.log` is written to the app's external files folder, so it can be read from the shell on release builds.

PC:
- **Direct route.** The gateway reaches the phone's own listener over the tailnet (`http://<phone tailnet IP>:18422/call`) when its `/health` answers for the expected device, and falls back to the relay. A call whose direct reply was lost is resent through the relay under the same call ID. The phone's request journal returns the saved result instead of running it twice.
- **Direct files.** Install and push downloads come from this computer over the tailnet (`BACKSTAGE_DIRECT_FILES_PORT`, tailnet peers only, `/v1/files/<id>` only). A failed direct download retries through the relay. Direct files are used only when the phone is reached over the tailnet. A phone reached over plain LAN (its Tailscale off) cannot reach this computer's tailnet address. On October 18, three pushes spent about 100 s on curl retries that way. curl's "Connection timed out" text was then read as a lost reply, so the relay retry never ran and the pushes ended as "unknown". A curl failure now counts as a definite tool failure.
- **Long calls through Cloudflare.** Cloudflare ends a proxied request that sends nothing for 100 s (error 524). A tool call still running after 25 s now sends its JSON status and headers early, then a space every 20 s until the result. JSON parsers skip the leading spaces. Before, long installs looked failed to the agent while the phone kept working, and the agent's retry joined the same call (October 14: three requests for one install, 385–790 s).
- The gateway reaches the relay on loopback (`BACKSTAGE_RELAY_INTERNAL_URL`) instead of through Cloudflare.
- The relay queues jobs for a phone seen in the last three minutes instead of refusing them as offline. Jobs never claimed before their deadline report "offline, not sent". Reusable files support byte ranges.
- **Agent bridge.** `agent.mjs serve` retries brief network drops up to three times. It resends anything whose connection never opened. It resends after a lost reply or a 502–504 only for MCP messages the gateway deduplicates; tool writes carry a requestId. Errors name the network cause (for example `UND_ERR_CONNECT_TIMEOUT`) instead of a bare "fetch failed". On October 19, those failures came from the PC's own uplink: a Google Drive upload saturated a weak 6 GHz link, with up to 27% packet loss and 150–800 ms to the router.

## Host settings

`~/.config/systemd/user/android-backstage-work.service.d/direct-route.conf`:

```ini
[Service]
Environment=BACKSTAGE_RELAY_INTERNAL_URL=http://127.0.0.1:18420
Environment=BACKSTAGE_DIRECT_FILES_URL=http://<this computer's tailnet IP>:18426
```

`~/.config/systemd/user/android-backstage-relay.service.d/direct-route.conf`:

```ini
[Service]
Environment=BACKSTAGE_DIRECT_FILES_PORT=18426
```

Set `BACKSTAGE_DIRECT_ROUTES=0` on the Work service to turn the direct route off.

## Reading the evidence

```sh
backstage shell 'cat /data/local/tmp/backstage/lifecycle.log'
backstage shell 'tail -50 /sdcard/Android/data/com.androidbackstage.app/files/supervisor.log'
backstage shell 'dumpsys adb | grep -c wifiAP'   # access points Android trusts
```

The `route` field in `~/.config/android-backstage/work/calls.jsonl` shows whether each call went `direct`, through the `relay`, or `relay_after_direct`.
