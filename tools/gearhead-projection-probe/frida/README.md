# Rooted-Maps pixel test

This is the UID-scoped hook used for the successful pixel capture in
[`../../../docs/research/gearhead-projected-activity.md`](../../../docs/research/gearhead-projected-activity.md).
It is a research harness, not a production AARE component.

The hook makes `Context.checkCallingPermission("android.permission.CAPTURE_VIDEO_OUTPUT")`
return `PERMISSION_GRANTED` **only** when the calling UID is the probe app's UID. Maps' own
signature check then passes for the probe, while every other caller keeps the normal result.
Maps is not patched or re-signed.

## Prerequisites

- Rooted device with `frida-server` running as root.
- Frida tools in a virtualenv (`pip install frida-tools`).
- Probe installed (`../build.sh && adb install -r ../build/probe.apk`).
- Node/npm, to bundle the Frida 17 Java bridge:

```sh
npm init -y
npm install frida-java-bridge frida-compile
./node_modules/.bin/frida-compile maps_capture_agent.js -o maps_capture_agent_compiled.js
```

Set `TARGET_UID` in `maps_capture_agent.js` to the probe's UID. Find it with:

```sh
adb shell dumpsys package net.prototux.probe | grep -m1 userId=
```

Frida 17 no longer provides a global `Java` binding; the script uses
`require('frida-java-bridge').default`.

## Run

```sh
python3 run_maps_capture.py
```

The driver force-stops Maps and the probe, spawns Maps, loads the compiled agent, resumes Maps,
and starts `net.prototux.probe/.ProbeActivity`. It waits for the probe to save frames under
`/sdcard/Android/data/net.prototux.probe/files/`, then prints the Frida log.

Expected log:

```
[hook] ContextImpl.checkCallingPermission installed
[hook] ContextWrapper.checkCallingPermission installed
[hook] ready
[hook] grant android.permission.CAPTURE_VIDEO_OUTPUT to uid=10358 (call 1)
```

Expected probe log:

```
onSetup accepted
onProjectionStart (tx 26) 1280x720@390 accepted
onProjectionResume (tx 4) accepted
frame 120: 1280x720 stride=5120 format=1
```

Pull the proof frame:

```sh
adb pull /sdcard/Android/data/net.prototux.probe/files/projected-frame-120.png
```

Frame 1 can be the pre-UI background. Use frame 60/120. Spawn a fresh Maps process for every
attempt: the hook is attached to one PID, and Maps restarts after a crash without it.
