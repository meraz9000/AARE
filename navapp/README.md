# AARE Nav

A small standalone Car App Library navigation app built on the **Google Navigation SDK for Android Auto**. It exists so AARE can host a touch-native map app (including in split screen) without reverse-engineering Google Maps' private `ICarProjection` surface.

This is a prototype, not a Play-ready app. Navigation SDK Android Auto is Preview (pre-GA); Google requires Navigation SDK access and Android Auto app approval.

## Requirements

- Google Maps Platform project with the **Navigation SDK** enabled.
- An API key restricted for this application (`net.prototux.aare.navigation`) with the Navigation SDK / Maps SDK for Android APIs enabled.
- You must accept the Navigation SDK terms from the phone UI before the map becomes usable.

## Build

Put the key in the repo's `local.properties`:

```properties
MAPS_API_KEY=AIza...
```

or pass it to Gradle:

```sh
./gradlew :navapp:assembleDebug -PMAPS_API_KEY=AIza...
```

Without a key the APK still builds, but AARE Nav shows a black map and logs:

```
W/AareNav: MAPS_API_KEY is empty; Navigation SDK cannot initialize
```

## Phone setup

```sh
adb install -r navapp/build/outputs/apk/debug/navapp-debug.apk
adb shell am start -n net.prototux.aare.navigation/.MainActivity
```

In AARE Nav:

1. **Accept Navigation SDK terms**.
2. Optionally **Set demo destination (Tulsa)** to start guidance.

## Use in AARE

AARE discovers `net.prototux.aare.navigation/.NavCarService` like any Car App Library app. The service is declared with the `NAVIGATION` category, `minCarApiLevel=7`, and `ALLOW_ALL_HOSTS_VALIDATOR` for local testing.

`NavScreen` creates a `VirtualDisplay` from the Car App `SurfaceContainer`, puts `NavigationViewForAuto` in a `Presentation`, and forwards:

- `onScroll` → `CameraUpdateFactory.scrollBy`
- `onScale` → `CameraUpdateFactory.zoomBy`
- `NavigationTemplate` map action strip includes `Action.PAN`

This is the documented Navigation SDK Android Auto map pattern. It should therefore keep working when AARE renders it in a 68/32 split pane, unlike Google Maps' legacy projected surface.

## Verification status

- Builds successfully with a placeholder/empty key.
- Installed and discovered by AARE: `AareCarApp: net.prototux.aare.navigation: library 1.8.0-rc01, API 7..8, negotiated 8`.
- AARE Nav creates the virtual display, shows the presentation, and logs `map ready`.
- With no API key the map is black; a real key and terms acceptance are still required before touch/render verification.
