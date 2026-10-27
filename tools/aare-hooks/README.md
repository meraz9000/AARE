# AARE Projection Hooks (Vector / LSPosed)

An on-device Xposed module that lets AARE host two apps that would otherwise reject it:

- **Google Maps** checks the signature permission `android.permission.CAPTURE_VIDEO_OUTPUT`
  inside its own process before it accepts a projection host. The module grants that check only
  when the caller is AARE's UID.
- **Waze** is a normal Car App Library app, but its `HostValidator` only accepts an allow-list of
  hosts. The module makes `HostValidator.isValidHost()` return true inside Waze's process.

The module is scoped to `com.google.android.apps.maps` and `com.waze`; its hooks do not run in
any other app. It does **not** modify either APK. A rooted phone with Vector (the current LSPosed
fork for Android 16/17) is required.

This replaces the laptop/Frida harness for everyday testing. Frida remains useful while
developing the hooks (see `../gearhead-projection-probe/frida/`).

## Requirements

- Rooted phone, Magisk 31+ recommended.
- [Vector](https://github.com/JingMatrix/Vector) v2.2 (3080) or newer installed as a Magisk
  module, with its manager app opened once.
- Android SDK build-tools and an Android 37 platform jar to build. The default paths are
  `$HOME/Library/Android/sdk/build-tools/36.0.0` and
  `$HOME/Library/Android/sdk/platforms/android-37.1/android.jar`; override with
  `BUILD_TOOLS`, `ANDROID_JAR`, or `ANDROID_HOME`.
- A debug keystore at `~/.android/debug.keystore` (or set `AARE_HOOKS_KEYSTORE`).

The Xposed API 82 compile-time jar is vendored at `lib/api-82.jar` (Apache-2.0, from the
Xposed/Interloc API distribution); it is not packaged into the APK.

## Build and install

```sh
cd tools/aare-hooks
./build.sh
adb install -r build/aare-hooks.apk
```

Enable and scope it with Vector's CLI (root shell):

```sh
adb shell su -c '/data/adb/modules/zygisk_vector/cli modules enable net.prototux.aare.hooks'
adb shell su -c '/data/adb/modules/zygisk_vector/cli scope set net.prototux.aare.hooks com.google.android.apps.maps/0 com.waze/0'
```

Force-stop Maps and AARE before the first test so the next Maps process loads the module:

```sh
adb shell am force-stop com.google.android.apps.maps
adb shell am force-stop net.prototux.aare
```

If the hook does not load, reboot once and check:

```sh
adb shell su -c '/data/adb/modules/zygisk_vector/cli modules ls'
adb logcat -s VectorLegacyBridge ProjectedHost
```

Expected logs include `AARE hooks: ContextImpl.checkCallingPermission installed`,
`AARE hooks: granted android.permission.CAPTURE_VIDEO_OUTPUT to AARE uid ...`, and the normal
`ProjectedHost` setup/resume lines.

## Test

`CarPreviewActivity` is not exported, so start it from a root shell:

```sh
adb shell su -c 'am start -W -n net.prototux.aare/.ui.CarPreviewActivity \
  --es launchPackage com.google.android.apps.maps'
```

Maps should render inside the AARE preview without Frida running. For a full head-unit test,
install AARE, launch it normally, connect `./headunit.sh --res 1280x720`, and tap the Maps tile;
the module is already active in the Maps process.

## Scope and limitations

- Maps only for the private `ICarProjection` path. Other legacy GMS projected apps have not been
  tried; add their service to `ProjectedAppCatalog.KNOWN` and scope the module to their package
  only after checking their permission checks.
- Waze still needs to be opened and signed in on the phone once. The hook only gets AARE past
  Waze's host validator; it does not bypass Waze's own setup or account checks.
- `CAPTURE_VIDEO_OUTPUT` is granted only to AARE's UID, resolved with
  `PackageManager.getPackageUid("net.prototux.aare")`. If the fork is renamed, update `AARE` in
  `HookEntry.java`.
- The module is a rooted development path, not a stock-device solution. It is intentionally
  small and readable; review it before installing.
