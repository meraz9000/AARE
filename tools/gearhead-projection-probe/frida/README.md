# Rooted Maps and Waze hooks

Maps enforces `android.permission.CAPTURE_VIDEO_OUTPUT` with `checkCallingPermission()` inside
its own projection service. That permission is `signature`, so neither AARE nor the probe can
request it normally. This hook makes the check return `PERMISSION_GRANTED` **only when the
calling UID belongs to AARE or the probe**; Maps itself is not patched or re-signed.

Waze uses the public Car App Library instead, but its `HostValidator` rejects AARE's signing
certificate. `waze_host_agent.js` is a separate test-only hook for that handshake. It is not
needed for Maps, and the Maps hook is not needed for Waze.

Both are development harnesses for the experimental integration, not a production permission
strategy.

## Prerequisites

- Rooted device with `frida-server` running as root (the server must be reachable with
  `frida-ps -U`).
- Frida tools in a Python virtualenv (`pip install frida-tools`).
- AARE installed (debug build is fine), or the probe installed for the standalone pixel test.
- Node/npm, to bundle the Frida 17 Java bridge:

```sh
npm init -y
npm install frida-java-bridge frida-compile
./node_modules/.bin/frida-compile maps_capture_agent.js -o maps_capture_agent_compiled.js
```

Frida 17 no longer provides a global `Java` binding; the script uses
`require('frida-java-bridge').default`.

The agent learns the UIDs at runtime from `PackageManager.getPackageUid()` for
`net.prototux.aare` and `net.prototux.probe`; it also has fallback UIDs and retries every 5 s.

## AARE integration

Build and install AARE, then run:

```sh
python3 run_aare.py
# or, when Maps is not the app you are testing:
python3 run_aare.py com.google.android.apps.maps
```

The driver force-stops Maps and AARE, spawns Maps, loads the hook, resumes Maps, and starts
`CarPreviewActivity` with `--es launchPackage com.google.android.apps.maps`. AARE's launcher
entry is the same path, so once the preview is open the Maps tile works normally.

`CarPreviewActivity` is not exported; the driver uses `su -c`, so the phone must be rooted.
Set `ANDROID_SERIAL` if more than one device is attached.

### Testing through the desktop head unit

```sh
python3 run_aare.py --car     # keep this running
# in another terminal, from the AARE repo root:
./headunit.sh --res 1280x720
```

`--car` starts `MainActivity` with the debug head-unit server on and remembers the package;
the first real `CarSession` opens that app when the catalog loads. This exercises the full
AAP path (encoder → head unit) instead of only the phone preview. The hook lives in the Maps
process, so the laptop and this script must stay connected for the whole session. With a
real car, use wireless Android Auto plus adb-over-Wi-Fi, or replace Frida with an on-device
hook (LSPosed/Magisk) so the laptop is not needed.

Expected host log:

```
Starting com.google.android.apps.maps…
bound com.google.android.apps.maps/com.google.android.apps.gmm.car.GmmCarProjectionService
onSetup (tx 1)
callback: connection state -2145910784
onProjectionStart (tx 26)
onProjectionResume (tx 4)
Projecting
```

The Maps map should appear inside AARE's car preview, with the AARE rail on the left. If Frida
is not attached, AARE shows the expected `CAPTURE_VIDEO_OUTPUT` error instead of pretending to
work.

## Waze host-validator hook

Waze is a public Car App Library app, but its `HostValidator` allow-list rejects AARE's debug
certificate. `waze_host_agent.js` is a separate, test-only hook that accepts AARE in the Waze
process; it does not touch Maps or the Waze APK. Compile it the same way:

```sh
./node_modules/.bin/frida-compile waze_host_agent.js -o waze_host_agent_compiled.js
python3 run_waze.py
```

`run_waze.py` spawns Waze, loads the hook, resumes it, and opens AARE's preview with
`--es launchPackage com.waze`. Without the hook, AARE logs
`SecurityException: Unrecognized host with uid 10360` and shows Waze's “Waze stopped” screen.
With it, Waze's own car UI starts at its “One-time setup” screen. Waze must then be opened and
signed in on the phone; full navigation in AARE still needs a test after that setup. This is a
host-identity workaround, not the `ICarProjection` protocol.

## Standalone probe

The original pixel-capture probe is still available:

```sh
../build.sh
adb install -r ../build/probe.apk
python3 run_maps_capture.py
adb pull /sdcard/Android/data/net.prototux.probe/files/projected-frame-120.png
```

`run_maps_capture.py` starts `net.prototux.probe/.ProbeActivity`; it does not touch AARE. Spawn
a fresh Maps process for every attempt: the hook is attached to one PID, and Maps restarts after
a crash without it.

Frame 1 can be the pre-UI background; use frame 60/120 for the rendered map.
