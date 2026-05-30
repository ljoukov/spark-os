# Spark OS

Spark OS is the workspace for turning the Sony Xperia Z2 Tablet Wi-Fi into a branded Spark AI education kiosk.

The tablet boots into the Spark kiosk app at:

`https://spark.eviworld.com/`

This repository stores the app source, Android runtime-resource overlays, boot branding assets, and operational notes. It intentionally does not store ROM zips, partition images, pulled system APKs, or other OS images.

## Layout

- `apps/spark-kiosk/` - Android kiosk shell app and boot animation assets.
- `overlays/system-bars/` - framework overlay for zero-height system bars and no software nav bar.
- `overlays/systemui/` - SystemUI overlay for zero bar dimensions while keeping SystemUI services alive.
- `overlays/latinime/` - LatinIME overlay that removes the voice key option.
- `docs/requirements.md` - product and system requirements.
- `docs/approach.md` - architecture and implementation approach.
- `docs/wifi-connectivity.md` - Wi-Fi setup and captive portal approach.
- `docs/ota-updates.md` - Spark-owned WebView and full-ROM OTA update strategy.
- `docs/operations.md` - build, install, recovery, and verification procedures.
- `docs/device-state.md` - current device state and observed versions.
- `docs/known-issues.md` - known risks and remaining checks.

## Current Target

- Device: Sony Xperia Z2 Tablet Wi-Fi, `castor_windy`.
- ADB serial: `CB51286PFD`.
- ROM: LineageOS 18.1, Android 11.
- Kiosk package: `com.eviworld.spark`.
- Device owner: `com.eviworld.spark/.SparkDeviceAdminReceiver`.

## Important Rule

Do not put ROM files, flashable zips, partition dumps, pulled `/system` APKs, or other OS images in this repository. Keep those in the external working directories used for flashing/recovery.
