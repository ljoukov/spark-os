# Known Issues And Risks

## SystemUI Services Must Stay Alive

Do not remove `com.android.systemui.statusbar.phone.StatusBar` or `com.android.systemui.keyguard.KeyguardViewMediator` from SystemUI startup.

Removing them produced a black-screen state:

- display awake
- Spark focused in WindowManager
- ActivityManager still sleeping/keyguard
- Spark window had no surface

The stable approach is to keep SystemUI alive and hide/zero its bars.

## StatusBar Window May Exist Internally

`dumpsys window` may still show a `StatusBar` window. That is acceptable if it is not visible and the user cannot pull down native Android UI. The internal service is needed for stable keyguard/window lifecycle.

## Keyboard Theme Follow-System Depends On Two Parts

Both must be true:

- LatinIME preference `pref_keyboard_theme_20140509` is string value `5`
- Spark dark/light switch updates Android `ui_night_mode`
- framework overlay sets `config_lockDayNightMode=false`

If LatinIME crashes with `Integer cannot be cast to String`, the preference was written as `<int>` instead of `<string>`.

If the keyboard stays dark or light after toggling Spark mode:

```sh
adb -s CB51286PFD shell cmd uimode night
adb -s CB51286PFD shell settings get secure ui_night_mode
adb -s CB51286PFD shell dumpsys package com.eviworld.spark | rg -n 'WRITE_SECURE_SETTINGS|granted=true'
```

and, from recovery, verify:

```xml
<string name="pref_keyboard_theme_20140509">5</string>
```

If `ui_night_mode` changes but `cmd uimode night` stays unchanged, verify the framework overlay includes:

```xml
<bool name="config_lockDayNightMode">false</bool>
```

If `ui_night_mode` does not change when toggling Spark mode, reinstall Spark and grant:

```sh
adb -s CB51286PFD install -t -r /home/liudmila/spark-os/apps/spark-kiosk/build/spark-kiosk.apk
adb -s CB51286PFD shell pm grant com.eviworld.spark android.permission.WRITE_SECURE_SETTINGS
adb -s CB51286PFD shell am start -S -n com.eviworld.spark/.MainActivity
```

`MODIFY_DAY_NIGHT_MODE` is requested in the manifest but is not grantable through `pm grant` on this ROM, so it is not the primary mechanism.

## Rotation Needs Physical Verification

The app now stores the display rotation when auto-rotate is disabled and re-applies it for the Spark lock overlay. Verify physically:

- rotate tablet to portrait
- open Spark controls
- disable Auto rotate
- press power
- wake
- confirm Spark lock screen is portrait
- unlock
- confirm Spark remains portrait

Repeat for landscape if needed.

## Recovery Can Auto-Return

Recovery has sometimes returned to Android after a short window. System partition changes should be scripted and executed promptly after `adb reboot recovery`.

## Generated Artifacts Are Excluded

This repo intentionally excludes:

- APK build outputs
- ROM zips/images
- pulled system APKs
- signing keystores

Recreate generated outputs from source when needed.
