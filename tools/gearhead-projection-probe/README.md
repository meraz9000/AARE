# gearhead projected-activity probe

A standalone Android app that speaks Google Maps' private projected-activity protocol:

```
bind GmmCarProjectionService
  → ICarProjection.onSetup(callback, ICar)          (transaction 1)
  → ICarProjection.onProjectionStart(display, spec) (transaction 26)
  → maps creates a Presentation on DrawingSpec.d
  → host reads RGBA frames from an ImageReader
```

The protocol is documented in
[`../../docs/research/gearhead-projected-activity.md`](../../docs/research/gearhead-projected-activity.md).
This directory is the runnable evidence: a host, an `ICar` stub, and a mock service that implements
the app side without Maps' permission gate.

## Results

### Stock phone: bind works, first transaction rejected

An ordinary app **can bind** `com.google.android.apps.maps/...GmmCarProjectionService` (it is
exported and has no manifest permission), but Maps rejects the first transaction:

```
java.lang.SecurityException: projection client manager does not have permission:
android.permission.CAPTURE_VIDEO_OUTPUT pid:... uid:...
```

`CAPTURE_VIDEO_OUTPUT` is a `signature` permission. `pm grant` cannot grant it. Root or a
Maps-process hook is required; see §5-6 of the research note.

### Rooted phone: real Maps navigation pixels

With the UID-scoped Frida hook in [`frida/`](frida/), the same unmodified probe got through the
check and Maps rendered its real projected navigation UI into `DrawingSpec.d`. The proof frame is
[`docs/screenshots/gearhead-projection-maps.png`](../../docs/screenshots/gearhead-projection-maps.png).
The host captured 1280×720 RGBA frames at stride 5120 from Maps' own `VRI[trf]` /
`SurfaceView[trf]` window, with no MediaProjection.

`onProjectionStart` alone is not enough: the probe follows it with `onProjectionResume` after
300 ms. It also supplies minimal non-null `CarInfo`/`CarUiInfo` objects and the
`ICarDisplayManager`/`ICarDisplay` binder chain (ICar transaction 77, display-manager transaction
3, display transaction 3). Those were required for Maps' UI setup to get past its null checks.

## Build

```sh
./build.sh
adb install -r build/probe.apk
```

Needs JDK 17+, Android SDK build-tools (aapt2, d8, zipalign, apksigner), and the standard
`~/.android/debug.keystore` (the script prints how to create one if missing).

## Run

Real Maps on a stock phone:

```sh
adb shell am start -n net.prototux.probe/.ProbeActivity
adb logcat -s ProjectionProbe ProjectedHost
```

You should see the bind succeed and `onSetup` fail with the `SecurityException` above.

Real Maps on a rooted phone with the Frida hook:

```sh
cd frida
# edit TARGET_UID in maps_capture_agent.js, then bundle it (see frida/README.md)
python3 run_maps_capture.py
```

The driver spawns Maps with the hook and starts the probe. Frames are saved every 60 frames; use
`projected-frame-120.png`, not the first frame (which may predate the UI).

Mock service (protocol self-test, works on an unrooted phone):

```sh
adb shell am start -n net.prototux.probe/.ProbeActivity --ez mock true
adb logcat -s ProjectionProbe ProjectedHost MockProjection
```

The mock creates a `VirtualDisplay` on the host's surface and shows a green
`Presentation`. The host saves the first received frame to
`/sdcard/Android/data/net.prototux.probe/files/projected-first-frame.png` and displays it in the
activity.

## Files

| file | purpose |
|---|---|
| `ProjectedHost.java` | client: binds the service, sends `onSetup`/`onProjectionStart`, implements `ICar`, reads frames |
| `MockProjectionService.java` | app-side stand-in: implements `ICarProjection`, reads `DrawingSpec`, shows a `Presentation` |
| `ProbeActivity.java` | minimal UI and logging; `--ez mock true` selects the mock |
| `AndroidManifest.xml` | requests `CAPTURE_VIDEO_OUTPUT` only to report grant state |
| `frida/` | UID-scoped Maps permission hook and spawn driver used for the rooted pixel test |

## Caveats

- The `ICar`/`ICarDisplay` stubs return minimal non-null objects. That is enough for Maps to
  instantiate its projected activity and draw; a production host needs real feature flags,
  display/region IDs, input forwarding and reconnect handling.
- The `DrawingSpec` encoder is hand-written because the class is a GMS `SafeParcelable` and is not
  in the Android SDK. It is version-sensitive; re-check the field tags against a new Maps/gearhead
  build.
- No production code here is integrated into AARE. `docs/research/gearhead-projected-activity.md`
  describes what the integration would need.
- Do not use this to bypass a permission on a device you do not own, and expect Google Maps updates
  to change the obfuscated class names and the check.
