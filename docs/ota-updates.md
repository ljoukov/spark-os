# OTA Update Strategy

Last updated: 2026-05-31.

## Summary

Spark should support over-the-air updates, but update authority should stay with Spark rather than the user-facing LineageOS updater UI.

Use three update lanes:

- Spark app updates for kiosk behavior and UI changes.
- WebView-only updates for browser-engine/security refreshes when Android accepts a standalone `com.android.webview` package update.
- Full ROM OTA updates for platform, framework, kernel, vendor, overlay, or recovery-level changes.

The normal production path should try the smallest safe update first. Full ROM OTA is the fallback when the issue cannot be fixed by an app or WebView package update.

## Current Device Facts

Current observed state on `CB51286PFD`:

```text
OS: LineageOS 18.1 / Android 11 / castor_windy
Build: 18.1-20260527-UNOFFICIAL-castor_windy
Android security patch: 2024-02-05
Vendor security patch: 2016-05-01
WebView provider: com.android.webview
WebView version: 148.0.7778.120
WebView path: /system/product/app/webview
```

Useful checks:

```sh
adb -s CB51286PFD shell dumpsys webviewupdate
adb -s CB51286PFD shell dumpsys package com.android.webview | rg -n 'versionCode|versionName|codePath|User 0'
adb -s CB51286PFD shell getprop ro.build.version.security_patch
adb -s CB51286PFD shell getprop ro.vendor.build.security_patch
adb -s CB51286PFD shell getprop ro.lineage.version
```

## CDN Contract

Spark should check a Spark-owned CDN endpoint for signed update metadata. The CDN is only a transport; the device must verify the metadata and payload before installing anything.

Recommended metadata shape:

```json
{
  "schema": 1,
  "generated_at": "2026-05-31T00:00:00Z",
  "device": "castor_windy",
  "channel": "stable",
  "updates": [
    {
      "kind": "webview",
      "package_name": "com.android.webview",
      "version_code": 777821500,
      "version_name": "148.0.7778.215",
      "min_current_version_code": 777812000,
      "url": "https://updates.example.com/castor_windy/webview/com.android.webview-148.0.7778.215.apk",
      "sha256": "hex-encoded-sha256",
      "size": 123456789,
      "rollout_percent": 10,
      "not_before": "2026-05-31T00:00:00Z"
    }
  ],
  "signature": "base64-signature-over-canonical-metadata"
}
```

Rules:

- Verify the metadata signature with a public key embedded in the Spark updater.
- Verify the payload SHA-256 before install.
- Reject payloads for the wrong device, package, channel, ABI, or SDK level.
- Reject stale or same-version payloads unless explicitly marked as a rollback package.
- Support staged rollout by stable device identity hashing, not by random choice on every check.
- Keep downloaded payloads outside the repository and outside user-visible storage.

## WebView-Only OTA

WebView-only OTA is the preferred engine refresh path when the update satisfies Android's WebView provider rules.

Eligibility checks before install:

- Package name is `com.android.webview`.
- Version code is newer than the installed package, unless an approved rollback is being performed.
- APK metadata matches the device ABI and Android version.
- The package signature is accepted as an update to the installed provider.
- The package installs successfully for user `0`.
- Android reports the installed package as the current valid WebView provider after update.

Implementation shape:

1. Spark updater checks the signed CDN metadata on a conservative cadence, such as once per day.
2. It downloads only when on validated Wi-Fi, battery is not low, and the device is idle or on external power.
3. It verifies metadata signature, payload hash, package name, version code, ABI, and SDK compatibility.
4. It installs the APK with Android `PackageInstaller` from Spark's device-owner context.
5. It verifies the active provider after installation using app-side WebView package APIs and, during operations, `dumpsys webviewupdate`.
6. It restarts Spark after a successful WebView update. Android may kill processes that have loaded the old WebView provider, so the restart should be treated as expected behavior.
7. It records update result, installed version, hash, timestamp, and failure reason in Spark-owned logs.

Operational verification:

```sh
adb -s CB51286PFD shell dumpsys webviewupdate
adb -s CB51286PFD shell dumpsys package com.android.webview | rg -n 'versionCode|versionName|User 0'
adb -s CB51286PFD shell am force-stop com.eviworld.spark
adb -s CB51286PFD shell am start -n com.eviworld.spark/.MainActivity
```

Expected result:

- `dumpsys webviewupdate` shows `com.android.webview` as current and valid.
- Spark launches and renders `https://spark.eviworld.com/`.
- Camera/file upload, offline page, top drawer, keyboard, and lock overlay still work.

## Full ROM OTA

Full ROM OTA should be used when WebView-only OTA is not valid or not sufficient.

Use full ROM OTA for:

- Android framework, media, networking, TLS, permission, package-manager, SystemUI, recovery, kernel, vendor, or firmware changes.
- Static overlay changes under `/system/product/overlay/...`.
- Boot animation or recovery-level branding changes that are not handled by the app.
- WebView updates that cannot be accepted as standalone package updates because of package validation, signing, ABI, SDK, or provider-eligibility constraints.
- Any update where the required files live under `/system`, `/product`, `/vendor`, boot, or recovery partitions.

Implementation shape:

1. Publish a signed full update package and signed metadata on the Spark CDN.
2. Spark updater downloads only when on validated Wi-Fi and suitable power conditions.
3. It verifies metadata signature, payload hash, target device, build lineage, and minimum current build.
4. It calls Android device-owner system-update APIs where supported by the ROM update package format.
5. If the current ROM cannot install that package format through device-owner APIs, keep the existing recovery/TWRP maintenance path and treat full ROM OTA as a future ROM-integration task.
6. After reboot, Spark verifies build fingerprint/version, WebView version, overlays, device-owner state, kiosk lock task, and app rendering.

Full ROM OTA must be staged more conservatively than WebView-only OTA. A broken ROM update can require recovery access, while a broken WebView update should normally be recoverable by reinstalling a known-good provider package or reflashing the tested image.

## Rollback

Keep one known-good WebView package and one known-good ROM package available on the CDN for each production channel.

Rollback rules:

- Rollback metadata must be explicitly marked; normal version checks should reject older versions.
- Rollback packages must pass the same signature and hash checks as forward updates.
- Spark should avoid automatic rollback loops. After one rollback attempt, require a newer metadata generation or admin intervention before trying again.
- If Spark cannot render after a WebView update, recovery should be possible through ADB or the full ROM maintenance path.

## Kiosk Policy

Hide user-facing update surfaces in normal kiosk mode. Spark owns update checks, prompts, logs, and installation timing.

Recommended policy:

- Keep `org.lineageos.updater` installed but hidden during normal kiosk operation.
- Do not expose Settings or updater screens as a user maintenance path.
- Surface update state only through Spark-owned admin UI or ADB logs.
- Never install updates while the user is actively using Spark unless the update is urgent and the device can return cleanly to the kiosk.

## Acceptance Criteria

Before enabling automatic rollout:

- WebView-only update has been tested on the target tablet model from a clean Spark kiosk state.
- A failed download, bad hash, bad signature, wrong package, wrong device, and interrupted install all fail closed.
- Spark restarts cleanly after WebView update.
- Offline page, Wi-Fi state display, file upload, camera upload, keyboard, rotation, lock overlay, and power behavior pass regression checks.
- Full ROM OTA has a separate tested recovery path.
- Update logs are available through ADB for support.
