# Projected Maps touch: investigation notes (2026-10-27)

Branch `experiment/scvh-touch`, Pixel 10 Pro XL `IWW6LVJPKJQ59I` (Android 17 / SDK 37, user build,
Magisk + Vector), AARE's own desktop head unit (`./headunit.sh`) at 1280×720.

**Bottom line:** car touches now reach Google Maps' map view with correct coordinates, timing and a
real input device, and Maps' gesture detector recognizes the drag. Maps still does not pan the map:
its scroll handler receives every movement but never queues a camera move. AARE is not asked
anything while this happens, so the remaining block is Maps-internal. This investigation was
stopped there. Drag-to-pan does work for Car App Library apps (Waze), where AARE does the gesture
detection itself.

Earlier notes said "a DHU drag moved the Maps camera" with
`docs/screenshots/aare-headunit-native-touch.png` as proof. That screenshot is a single frame of
Maps' default camera position (Tulsa, no location fix), the same view Maps shows before any touch,
so it does not show the camera moving. Treat that claim as unverified.

## What was wrong in AARE, and what was fixed

| Commit | Problem | Fix |
|---|---|---|
| `1a966b4` | The branch removed the touch overlay whenever the SurfaceView had an input token, even when the VirtualDevice was missing and car touches were synthetic View events; `setTouchableRegion(0,0,w,h)` was in window coordinates; a VirtualDevice without a touchscreen swallowed every touch; `ACTION_CANCEL` was sent as a finger (rejected) and left the pointer stuck down. | Use the SurfaceControlViewHost path only when a VirtualTouchscreen exists; keep the tx 7 overlay until Maps' SurfacePackage is attached; drop the touchable-region call; fall back when there is no touchscreen; send cancel as `TOOL_TYPE_PALM`; clamp coordinates. |
| `a3316f5` | `1a966b4` did not compile (`TouchBuilder.m()` used a constructor parameter). | Keep the class as a property. |
| `f45dbcd` | **Touches never reached Maps.** A `SurfaceView` sits behind its window, and so does the Maps window embedded in it. AARE's own car window covers the whole car display with a touchable region, so InputDispatcher hit-tested every VirtualTouchscreen event to AARE. | `setZOrderOnTop(true)` once the SurfacePackage is attached (back to `false` on error, Retry and surface teardown so AARE's error UI stays visible). Verified with `dumpsys input`: `TouchedWindows` on the car display is now `Embedded{-net.prototux.aare}`. |
| `772828a` | `ProjectedHost.oneWay()` passed flags `0`, so tx 7 touch, key, pause, resume and focus were synchronous binder calls. From the touch overlay this blocked AARE's main thread on Maps → "AARE isn't responding" in under 10 s. | `IBinder.FLAG_ONEWAY`. Also added the `debug.aare.touch` switch and Maps-side touch logging (below). |
| `af03213` | — | Debug trace of every call Maps makes into AARE's ICar binders. |

## What the traces showed

1. **Routing (dumpsys input).** The VirtualTouchscreen (`AARE-TOUCHSCREEN`) exists, is associated
   with the "AARE car" virtual display, and receives every head-unit tap and drag. After
   `f45dbcd` the touched window is Maps' embedded window. Maps' embedded window reports
   `globalScale=0`; this turned out not to matter (coordinates arrive correctly).
2. **Arrival in Maps (`AareHooksTouch`).** Each touch reaches the root of Maps' car window on the
   car display with correct coordinates (car x minus the 96 px side rail), event time within
   ~10 ms of delivery, source touchscreen, real device id. The root reports `handled=true`.
3. **Consumer.** The touch-target chain ends at Maps' full-screen map `SurfaceView` (obfuscated
   `bvtk`, 1184×720) and its `OnTouchListener`.
4. **Gesture recognition.** Maps' gesture detector turns the drag into scroll callbacks with sensible
   distances (e.g. dx≈38, dy≈19 per step for a diagonal drag).
5. **No camera move.** The scroll callback does not queue a camera update. On Maps' render thread
   the per-frame "apply pending camera updates" step returns 0 throughout the drag, and Maps keeps
   producing frames (SurfaceFlinger frame numbers increase) while the map pixels stay identical.
6. **No ICar traffic during the drag (`AareICarTrace`).** Maps' only ICar queries are at setup:
   `car_module_feature_set` (tx 55, many times), `client_fullscreen_presentation` and
   `presentation_clear_parent_padding` (tx 71), `rotary_use_focus_finder` (tx 19), plus tx 1, 2, 5
   and 77. AARE answers 0 for the tx 19/71 settings. `CarUiInfo` already reports a capacitive
   touchscreen.
7. **tx 7 path.** With `debug.aare.touch=tx7` the drag is delivered over ICar tx 7 instead; the map
   does not pan that way either.
8. **Ruled out:** routing/z-order (fixed), coordinate scaling, `globalScale`, event timestamps,
   rendering being frozen, the touchscreen flag in `CarUiInfo`, the Parked/driving toggle in the
   head unit.

## Debugging aids left in the code

- `adb shell setprop debug.aare.touch tx7` keeps AARE's tx 7 overlay on the SurfacePackage path
  (clear with `setprop debug.aare.touch ''`; takes effect when Maps next attaches).
- `adb logcat -s AareICarTrace` — every inbound ICar / ICarDisplay / ICarDisplayManager /
  ICarProjectionCallback transaction with its leading string and int arguments.
- `adb logcat -s AareHooksTouch` (aare-hooks module, Maps process) — each touch at the root of every
  Maps window, plus the view chain that consumed it. The hooks only fire because they ask Vector to
  deoptimize the framework callers (`ViewRootImpl`, `InputEventReceiver`); without that, boot-image
  inlining bypasses them on this phone.
- `adb logcat -s AareProjected ProjectedHost` — attach/projection lifecycle.

## Gotchas found along the way

- The phone's default 256 KB log buffer drops session-start lines quickly; use `adb logcat -G 16M`.
- **Retry after Maps dies does nothing.** `ProjectedHost.startInternal()` returns early because the
  surface/size are unchanged and the old `connection` is never cleared on service death. Workaround:
  leave the Maps screen (car Home) and open Maps again. Not fixed.
- AARE installed as an updated system app (Magisk module) only accepts updates signed with the same
  key as the module's APK (`~/.android/debug.keystore` on the Mac). A cloud/CI debug build must be
  re-signed with that key (`apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android`).
- `launch-headunit.command` (repo root) starts `./headunit.sh` by double-click on macOS.

## If someone picks this up again

The AARE side is no longer the obstacle. Getting Maps to pan would mean understanding Maps'
internal reason for not applying its own scroll, which this work did not pursue. For a touch-driven
map in AARE, prefer Car App Library navigation apps (Waze): AARE detects the gestures and calls the
app's `SurfaceCallback.onScroll/onScale/onFling`, a public interface AARE controls.

## 2026-10-27: display-id mismatch and tx 71 (unverified)

Stock gearhead's `jnt.aR()` builds the `DrawingSpec` with the **display id of the window that
hosts the projection**, not display 0:

```java
int i = this.M; // display id delivered with the InputTransferToken
Bundle bundle = new Bundle();
bundle.putParcelable("input_transfer_token", parcelable);
bundle.putInt("com.google.android.gms.car.EXTRA_CAR_ACTIVITY_FLAGS", true != aJ() ? 128 : 0);
new DrawingSpec(width, height, density, surface, insets, flags, i, bundle);
```

AARE was sending `CarDisplayId = 0` in `onProjectionStart`, `DrawingSpec.displayId = 0`,
`onProjectionResume(0)`, and display 0 for touch/key. On the SCVH path Maps' `bemp.u()` uses
`drawingSpec.g` to pick the display and creates its window context on it. With 0, Maps creates its
SCVH window against the **phone display**; the map's internal SurfaceView measured 2170x1080 while
the car content was 1184x720. That is a credible reason for the map camera/UI to behave differently
even though touches arrive at the right view.

`0de77e4` changes `ProjectedHost` to use `view.display.displayId` (the AARE VirtualDevice display)
for all of those values. It also answers tx 71 with `1` for `client_fullscreen_presentation` and
`presentation_clear_parent_padding` instead of 0. Build passed; **not yet tested on the phone** —
the device disconnected before the install. Next step: reconnect the Pixel, install, restart
AARE + DHU + Maps, confirm `ProjectedHost: start view ... display=<car id>` and
`onProjectionStart ... display=<car id>`, then check whether Maps now shows full AA chrome and pans.
