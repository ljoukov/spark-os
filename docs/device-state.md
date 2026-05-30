# Device State

Last updated: 2026-05-30.

## Hardware

- Model: Sony Xperia Z2 Tablet Wi-Fi.
- Device codename: `castor_windy`.
- ADB serial: `CB51286PFD`.
- Display: 1920x1200 landscape native, 240 dpi.
- Native status bar height used by Spark top bar: 24 dp / 36 px.

## OS

- ROM: LineageOS 18.1.
- Android: 11.
- Observed build lineage property: `18.1-20260527-UNOFFICIAL-castor_windy`.
- Android build base observed earlier: `RQ3A.211001.001`.
- WebView provider: `com.android.webview`.
- WebView version observed: `148.0.7778.120`.

## Spark App

- Package: `com.eviworld.spark`.
- Main activity: `com.eviworld.spark/.MainActivity`.
- Device owner: `com.eviworld.spark/.SparkDeviceAdminReceiver`.
- Home app: Spark.
- Lock task: active.
- URL: `https://spark.eviworld.com/`.

## Current Behavior Verified

- Black-screen/keyguard stuck state was fixed by restoring SystemUI service startup.
- Spark activity resumes and receives focus.
- Android keyguard is disabled.
- Bottom swipe does not show Android navigation.
- Top swipe opens Spark controls.
- Power button sleeps the tablet.
- Wake shows Spark-owned lock overlay.
- Spark lock overlay unlocks with swipe-up after raw-coordinate gesture fix.
- Double-press power reports `MULTI_PRESS_POWER_NOTHING`.
- LatinIME is default/enabled input method.
- LatinIME preference crash was fixed by changing theme preference from int to string.
- LatinIME theme preference is now `5`, follow system.
- Framework overlay now sets `config_lockDayNightMode=false`.
- Spark dark/light switch was verified to toggle Android night mode:
  - dark: `ui_night_mode=2`, `Night mode: yes`, `mNightModeLocked=false`
  - light: `ui_night_mode=1`, `Night mode: no`, `mNightModeLocked=false`
- Current landscape transport lock was verified: power sleeps, wake shows Spark lock screen in landscape, swipe-up unlocks back to Spark.
- Portrait transport lock path is implemented in code but still needs a physical orientation check with the tablet held in portrait.

## Current Overlay Packages

- `com.eviworld.spark.systembars.overlay`
- `com.eviworld.spark.systemui.overlay`
- `com.eviworld.spark.latinime.overlay`

## Important Recovery Detail

Normal Android ADB root is disabled. Recovery ADB shell is root and should be used for:

- replacing static overlays under `/system/product/overlay`
- editing LatinIME protected preferences under `/data/user_de/0/...`
- removing stale overlay idmap files under `/data/resource-cache`
