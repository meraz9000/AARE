#!/usr/bin/env bash
# Prepare a rooted phone for AARE's trusted VirtualDevice + VirtualTouchscreen path.
#
# AARE's projected Maps hosting needs its own input channel. A plain DisplayManager
# VirtualDisplay has none; Android 14+ can create a trusted virtual display and an
# associated VirtualTouchscreen through CompanionDeviceManager. That requires:
#   - AARE to hold the COMPANION_DEVICE_APP_STREAMING role (or an equivalent
#     self-managed association allowed by VirtualDeviceManagerService)
#   - CREATE_VIRTUAL_DEVICE and ADD_TRUSTED_DISPLAY, which are role-granted
#   - one CDM association owned by AARE
#
# This is invasive and phone-specific. Run it only on a rooted development phone.
set -euo pipefail

ADB=${ADB:-adb}
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB="$ADB -s $ANDROID_SERIAL"
fi
PKG=net.prototux.aare
PROFILE=android.app.role.COMPANION_DEVICE_APP_STREAMING
MAC=${AARE_CDM_MAC:-02:00:00:00:00:03}

if ! $ADB shell su -c id 2>/dev/null | grep -q 'uid=0'; then
  echo "error: adb root/su is required" >&2
  exit 1
fi

echo "granting role-managed permissions to $PKG"
$ADB shell su -c "pm grant $PKG android.permission.CREATE_VIRTUAL_DEVICE" || true
$ADB shell su -c "pm grant $PKG android.permission.ADD_TRUSTED_DISPLAY" || true

echo "checking companion-device associations"
$ADB shell su -c "cmd companiondevice list" || true

echo "creating AARE's app-streaming association (ignored if it already exists)"
$ADB shell su -c "cmd companiondevice associate 0 $PKG $MAC $PROFILE false" || true

echo
echo "associations now:"
$ADB shell su -c "cmd companiondevice list" || true
echo
echo "Install tools/aare-hooks/build/aare-hooks.apk, enable it in Vector/LSPosed,"
echo "and scope it to com.google.android.apps.maps and com.waze."
echo "AARE will try association IDs 3,0,2,4..15; ID 1 is deliberately skipped."
