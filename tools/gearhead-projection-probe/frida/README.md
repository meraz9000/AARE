# Rooted Maps permission hook

Maps enforces `android.permission.CAPTURE_VIDEO_OUTPUT` with `checkCallingPermission()` inside
its own projection service. That permission is `signature`, so neither AARE nor the probe can
request it normally. This hook makes the check return `PERMISSION_GRANTED` **only when the
calling UID belongs to AARE or the probe**; Maps itself is not patched or re-signed.

It is a development harness for the `PROJECTED` feature, not a production permission strategy.

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
