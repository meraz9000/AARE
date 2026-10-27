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

## Result on a stock phone

An ordinary app **can bind** `com.google.android.apps.maps/...GmmCarProjectionService` (it is
exported and has no manifest permission), but Maps rejects the first transaction:

```
java.lang.SecurityException: projection client manager does not have permission:
android.permission.CAPTURE_VIDEO_OUTPUT pid:... uid:...
```

`CAPTURE_VIDEO_OUTPUT` is a `signature` permission. `pm grant` cannot grant it. Root or a
Maps-process hook is required; see §5-6 of the research note. This probe is useful for testing
that path, and for validating the protocol against the mock on any device.

## Build

```sh
./build.sh
adb install -r build/probe.apk
```

Needs JDK 17+, Android SDK build-tools (aapt2, d8, zipalign, apksigner), and the standard
`~/.android/debug.keystore` (the script prints how to create one if missing).

## Run

Real Maps:

```sh
adb shell am start -n net.prototux.probe/.ProbeActivity
adb logcat -s ProjectionProbe ProjectedHost
```

You should see the bind succeed and `onSetup` fail with the `SecurityException` above.

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

## Caveats

- The `ICar` stub returns empty/null values for most queries. That is enough for Maps to instantiate
  its projected activity and draw a first frame in the mock; a production host needs `CarUiInfo`,
  feature flags, display/region IDs and input forwarding.
- The `DrawingSpec` encoder is hand-written because the class is a GMS `SafeParcelable` and is not
  in the Android SDK. It is version-sensitive; re-check the field tags against a new Maps/gearhead
  build.
- No production code here is integrated into AARE. `docs/research/gearhead-projected-activity.md`
  describes what the integration would need.
- Do not use this to bypass a permission on a device you do not own, and expect Google Maps updates
  to change the obfuscated class names and the check.
