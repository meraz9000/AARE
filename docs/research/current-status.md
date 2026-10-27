# Current status — projected Google Maps in AARE

Last updated: 2026-10-27 on branch `experiment/scvh-touch`.

This file is the hand-off note for a reviewer. It describes what was built, what is proven, and
what is still open. The detailed protocol reverse-engineering is in
[`gearhead-projected-activity.md`](gearhead-projected-activity.md); the original gearhead notes
are in [`gearhead-notes.md`](gearhead-notes.md).

## What works

- AARE can bind Google Maps' private `com.google.android.gms.car.ICarProjection` service
  (`com.google.android.apps.maps/com.google.android.apps.gmm.car.GmmCarProjectionService`),
  complete `onSetup`, `onProjectionStart`, and `onProjectionResume`, and render Maps in the car
  UI.
- The display path uses Maps' modern `SurfaceControlViewHost` mode:
  - AARE advertises `SURFACE_CONTROL_VIEW_HOST_RENDERING` in ICar tx 55.
  - It puts the AARE `SurfaceView`'s `InputTransferToken` in `DrawingSpec.h`.
  - Maps returns a `SurfacePackage` in callback tx 35; AARE embeds it with
    `SurfaceView.setChildSurfacePackage()`.
- Native single-finger touch works through an AARE-owned trusted **VirtualDevice** display and
  an associated **VirtualTouchscreen**. Car/DHU touch is sent as `VirtualTouchEvent`s on a
  background executor. A DHU drag moved the Maps camera (proof screenshots
  `docs/screenshots/aare-headunit-native-touch.png`, and user captures from the test session).
- The on-device Vector/LSPosed module grants `CAPTURE_VIDEO_OUTPUT` only to AARE's UID in the
  Maps process, and bypasses Waze's `HostValidator` only for AARE's package. No Frida is needed
  after the module is installed.

## What is not proven / known open issues

- **Pinch-to-zoom** is not verified. The DHU is single-pointer. A two-finger `sendevent` test
  was attempted against `/dev/input/event3` (`AARE-TOUCHSCREEN:26`) but the shell script was
  denied access to the event node; use an in-app injector or a multi-touch-capable car/DHU.
- **Cut-off / overscan on the car screen** is not verified. `CarSession` now honors explicit
  per-side `ui_config.margins` and converts pixels to dp correctly. If the car sends zero
  margins but still crops, a user-configurable safe area is still needed.
- **Real Kia EV6 test** is pending. The stock Google DHU and AARE's own DHU are the only full-AAP
  paths tested. The car needs AARE's imported phone certificate (the local `gearhead2` import)
  and the VirtualDevice setup below.
- **Nuvio Cast surface touch** was not made to work. Its Car App Library `onClick` injection path
  never received taps in AARE's surface host.
- **Waze** reaches its one-time setup screen with the HostValidator hook; full navigation needs
  Waze signed in on the phone. This is separate from the Maps private API.
- The current code has test-only infrastructure: a trusted VirtualDevice display, a Companion
  Device app-streaming association, and role-managed permissions. It is invasive and must be
  documented/set up on each device.

## Required rooted-device setup

Test device: Pixel 10 Pro XL `IWW6LVJPKJQ59I`, Android 17 (SDK 37), Magisk 31.1, Vector 2.2.
AARE UID 10360; Maps UID 10246.

1. Build/install AARE and the hooks module:
   ```sh
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   tools/aare-hooks/build.sh
   adb install -r tools/aare-hooks/build/aare-hooks.apk
   ```
   Enable the module in Vector/LSPosed and scope it to
   `com.google.android.apps.maps` and `com.waze` (gearhead only while debugging).
2. Create AARE's CompanionDeviceManager association and grant the role-managed permissions:
   ```sh
   tools/aare-virtualdevice-setup.sh
   ```
   On the test phone this created association ID 3 with profile
   `android.app.role.COMPANION_DEVICE_APP_STREAMING`.
3. Launch AARE's car UI (preview or head-unit mode):
   ```sh
   adb shell su -c 'am start -W -n net.prototux.aare/.ui.MainActivity \
     --ez hu_server true --es launchPackage com.google.android.apps.maps'
   adb forward tcp:5277 localabstract:aare_hu
   # start Google's stock DHU, or AARE's ./headunit.sh
   ```
4. Open the Maps tile (or use `--es launchPackage`) and check that Maps renders. Touch should
   arrive through the VirtualTouchscreen.

## Key source files

- `app/src/main/java/net/prototux/aare/host/ProjectedHost.kt`
  — binds Maps, ICarProjection/ICar/ICarDisplay stubs, feature set, tx 26/4/20/35, DrawingSpec,
  SCVH package handling.
- `app/src/main/java/net/prototux/aare/host/ProjectedAppScreen.kt`
  — SurfaceView host, focus/touchable region, Maps screen.
- `app/src/main/java/net/prototux/aare/host/ProjectedAppCatalog.kt`
  — Maps service entry.
- `app/src/main/java/net/prototux/aare/video/VirtualDeviceDisplay.kt`
  — trusted VirtualDevice display + VirtualTouchscreen, VirtualTouchEvent encoding.
- `app/src/main/java/net/prototux/aare/video/ProjectionDisplay.kt`
  — Presentation and native touch dispatch.
- `app/src/main/java/net/prototux/aare/car/CarSession.kt`
  — margins/insets and touch routing.
- `tools/aare-hooks/src/net/prototux/aare/hooks/HookEntry.java`
  — UID-scoped `CAPTURE_VIDEO_OUTPUT` and Waze HostValidator hooks.
- `tools/aare-virtualdevice-setup.sh`
  — role/permission/association bootstrap.
- `tools/gearhead-projection-probe/`
  — standalone probe and Frida harnesses from the reverse-engineering phase.

## History

The initial research/probe (Frida UID hook) is documented in
`gearhead-projected-activity.md`; it proved Maps could render with a host-provided
`DrawingSpec` surface. The current branch replaces the synthetic touch experiments with native
VirtualTouchscreen input. `be14d53` on `main` contains the earlier raw-multi-touch/margin work;
this branch is intentionally WIP until the car test and pinch validation are complete.

## Immediate next steps

1. Verify pinch with a two-pointer injector/car and confirm the map zooms.
2. Verify margins/cut-off on the EV6 and add a safe-area setting if needed.
3. Clean any remaining debug logging, run `./gradlew :app:assembleDebug :app:testDebugUnitTest`,
   commit the working branch, and merge after car validation.
4. Re-test audio/GPS/touch after a clean car connection; re-enable gearhead only if needed for
   comparison.
