# Approach

## Base System

The tablet runs LineageOS 18.1 / Android 11 on the Sony Xperia Z2 Tablet Wi-Fi (`castor_windy`). This keeps the device-specific kernel and drivers while providing a much newer Android userspace than the original Sony software.

The design is not a custom Linux distribution. It is an Android kiosk ROM/profile:

- Android still owns hardware, power, Wi-Fi, input, WebView, camera intents, and package management.
- Spark owns the user-facing shell.
- Small static overlays change framework/SystemUI/LatinIME resources.
- The Spark app is device owner and home app.

## Kiosk App

The kiosk app is `com.eviworld.spark`.

Main responsibilities:

- open `https://spark.eviworld.com/` in WebView
- show an offline/retry page if Spark starts before connectivity is available
- keep retrying first load through a network callback plus a short watchdog until Spark has loaded once
- draw Spark top bar
- draw Spark top-swipe controls
- draw Spark transport lock screen
- handle brightness, rotation, dark mode, and reload
- bridge web file chooser to Android document/camera intents
- start lock task mode
- apply device-owner policy
- configure LatinIME as the only enabled/default keyboard
- disable camera power gestures

The app intentionally uses WebView rather than embedding a browser app. That keeps the user inside Spark while preserving modern browser rendering where WebView supports it.

## System UI Strategy

Android app immersive flags alone are not enough because system bars can transiently appear on edge swipes. Device-owner APIs help, but the strongest result comes from combining:

- device owner `setStatusBarDisabled(true)`
- lock task mode with `LOCK_TASK_FEATURE_NONE`
- app-level immersive flags
- framework overlay that makes nav/status dimensions zero
- SystemUI overlay that makes bar dimensions zero

Important: do not remove `StatusBar` or `KeyguardViewMediator` from `config_systemUIServiceComponents`.

We tried removing those services and Android became internally stuck: the display was awake, Spark was the focused app, but ActivityManager still showed sleeping/keyguard state and the Spark window had no surface. Keeping those controller services alive while making the bars invisible is the stable approach.

The current `overlays/systemui/res/values/arrays.xml` is intentionally empty:

```xml
<resources />
```

That means the overlay no longer changes SystemUI service startup.

Android Settings is intentionally unreachable from the tablet UI once kiosk mode is active. Routine runtime settings are handled by the Spark app or ADB `settings` commands. Root filesystem changes should be made from recovery/TWRP, not by trying to open Settings from the device.

## Wi-Fi Connectivity Strategy

Wi-Fi should remain Android-owned. Spark should show network state and offline recovery UI, but the first supported connection flow should temporarily hand off to Android's Wi-Fi settings/panel and `CaptivePortalLogin`.

Recommended implementation:

- show `Connect Wi-Fi` only from the offline screen or an admin-only drawer gesture
- temporarily allow `com.android.settings` and `com.android.captiveportallogin` in lock task mode
- temporarily unhide Android Settings if Spark has hidden it with device-owner policy
- launch directly into Wi-Fi settings or the Wi-Fi panel
- let Android handle SSID selection, passwords, saved networks, enterprise Wi-Fi, captive portals, and validation
- return to Spark when the active network has `NET_CAPABILITY_VALIDATED`
- re-apply the normal kiosk policy after returning

This is preferred over a Spark-native Wi-Fi UI because captive portal flows depend on Android loading a login page over an unvalidated Wi-Fi network and then re-checking connectivity. Hotel, school, and enterprise networks often rely on Android's built-in behavior.

The more branded alternative is possible but materially larger: Spark would need network scanning, location-permission handling, password and enterprise credential UI, privileged/system-app or ROM-level connection rights, captive portal detection, a portal browser bound to the captive network, and a clean handoff back to Android validation.

See [Wi-Fi Connectivity Approach](wifi-connectivity.md) for the detailed implementation shape.

## OTA Update Strategy

Spark should own update checks and installation timing instead of exposing Android's user-facing updater UI in kiosk mode.

The update approach uses three lanes:

- Spark app updates for kiosk UI and policy behavior.
- WebView-only OTA for `com.android.webview` engine refreshes when Android accepts the package as a valid provider update.
- Full ROM OTA for framework, kernel, vendor, overlay, boot, recovery, or other platform changes.

The smallest safe update should be preferred. Full ROM OTA is reserved for cases where an app or WebView package update cannot carry the required change.

See [OTA Update Strategy](ota-updates.md) for the detailed implementation shape.

## Spark Controls Drawer

The Spark controls are an app-owned top drawer opened by a downward swipe from the top edge. It is centered, constrained to the actual current app width, and uses a bottom gripper so it does not read as a full-screen native shade.

The drawer owns these controls:

- brightness slider
- rotation lock / auto-rotate icon
- dark/light mode icon
- reload icon

The drawer closes only from an outside tap or from the gripper. It does not attach a broad vertical swipe handler to the sheet body, because that steals touch events from the brightness slider.

The icon set is based on Google Material vector icons. The dark-mode icon uses the outlined/even-odd crescent rather than the filled moon, because the filled Material icon looked too heavy inside the circular button at this size.

## Runtime Resource Overlays

Four overlays are used:

- `com.eviworld.spark.systembars.overlay`
  - target: `android`
  - sets system/nav/status dimensions to zero
  - sets `config_showNavigationBar=false`
  - sets `config_lockDayNightMode=false` so Spark can switch Android day/night mode for LatinIME
- `com.eviworld.spark.systemui.overlay`
  - target: `com.android.systemui`
  - sets SystemUI bar dimensions to zero
  - does not remove SystemUI services
- `com.eviworld.spark.latinime.overlay`
  - target: `com.android.inputmethod.latin`
  - sets `config_enable_show_voice_key_option=false`
- `com.eviworld.spark.snapcamera.overlay`
  - target: `org.lineageos.snap`
  - overrides Snap's tablet `shutter_offset` so camera controls are not clipped by Spark's full-height kiosk display frame

These are static overlays installed under `/system/product/overlay/...` from recovery.

## Keyboard Strategy

The custom Spark keyboard was removed. Stock LatinIME is used because it provides correct native behavior for:

- key feedback
- shift state
- delete repeat/behavior
- touch correction
- suggestions/autocorrect
- layout quality

LatinIME preferences are patched in device-protected storage:

- `pref_key_use_personalized_dicts=false`
- `pref_key_use_contacts_dict=false`
- `pref_voice_input_key=false`
- `pref_keyboard_theme_20140509="5"`

Theme value `5` means follow system. Value `4` is hard-coded Material Dark. The value must be a string, not an int; writing it as `<int ...>` crashes LatinIME with `Integer cannot be cast to String`.

Spark's dark/light switch now also sets Android `ui_night_mode` so LatinIME can follow the same mode.

## Rotation Strategy

The Spark menu's auto-rotate switch controls both Android rotation settings and the app's requested orientation.

When auto-rotate is turned off, the app stores the current display rotation in `PREF_USER_ROTATION`. The transport lock re-applies that stored rotation on screen-off and screen-on, so a portrait-locked tablet shows the Spark lock screen in portrait and unlocks back into portrait.

When auto-rotate is enabled, the lock screen is allowed to continue using the sensor.

The app manifest uses `screenOrientation="user"` instead of `fullSensor`. When Spark rotation lock is active, the app enforces the saved orientation during resume, focus, pause, stop, and screen-off. This avoids a transient sensor/gravity orientation frame when the power button is pressed or the screen wakes.

Spark also stores an explicit Android orientation constant alongside the display rotation. If old saved values disagree, the orientation is recomputed from the saved rotation rather than trusting stale data.

## Power Strategy

Spark removes `FLAG_KEEP_SCREEN_ON`, writes `screen_off_timeout=120000`, and also schedules its own inactivity lock after 2 minutes of user inactivity.

Short-press power sleeps the device and marks Spark's transport lock state. Long-press power is configured to Android's confirm-shutdown behavior through global power-button settings.

## Boot/Recovery Strategy

Normal boot should show Spark branding and launch Spark automatically.

Recovery remains available for maintenance. Recovery is used for root filesystem access because normal Android ADB root is disabled on this build. System overlays and LatinIME protected preferences are patched from recovery.

The boot animation is a landscape system asset because the bootloader/boot animation path is not app-resource aware. The app splash is different: Android resource qualifiers are used so `drawable-nodpi/spark_splash.png` is landscape and `drawable-port-nodpi/spark_splash.png` is portrait. That prevents the app window background from stretching the landscape Spark AI image during portrait launch or lock transitions.
