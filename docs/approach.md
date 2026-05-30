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

## Runtime Resource Overlays

Three overlays are used:

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

## Boot/Recovery Strategy

Normal boot should show Spark branding and launch Spark automatically.

Recovery remains available for maintenance. Recovery is used for root filesystem access because normal Android ADB root is disabled on this build. System overlays and LatinIME protected preferences are patched from recovery.
