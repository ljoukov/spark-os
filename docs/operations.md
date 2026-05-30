# Operations

## Build Kiosk APK

```sh
cd /home/liudmila/spark-os/apps/spark-kiosk
./tools/build.sh
```

The build script expects:

- Android SDK/build tools under `/home/liudmila/android-toolchain/sdk`
- JDK under `/home/liudmila/android-toolchain/jdk`

It writes generated output under `apps/spark-kiosk/build/`. Build output is not committed.

The local ignored file `apps/spark-kiosk/spark-debug.keystore` is copied from the original working tree so newly built APKs can upgrade the app that is already installed on the tablet. Do not commit that keystore.

## Install Kiosk APK

```sh
adb -s CB51286PFD install -t -r /home/liudmila/spark-os/apps/spark-kiosk/build/spark-kiosk.apk
adb -s CB51286PFD shell pm grant com.eviworld.spark android.permission.WRITE_SECURE_SETTINGS
adb -s CB51286PFD shell am start -S -n com.eviworld.spark/.MainActivity
```

ADB may first try incremental install and then fall back to streamed install. The fallback is expected on this build.

`WRITE_SECURE_SETTINGS` is needed so Spark's dark/light switch can update Android `ui_night_mode`, which LatinIME uses when its keyboard theme is set to follow system.

The app also requests `MODIFY_DAY_NIGHT_MODE`, but this permission is not grantable with `pm grant` on the current ROM. The app therefore uses secure settings/device-owner fallbacks for night mode.

## Device Owner

Current device owner:

```text
com.eviworld.spark/.SparkDeviceAdminReceiver
```

If rebuilding from a freshly wiped device, set device owner before normal user setup diverges:

```sh
adb shell dpm set-device-owner com.eviworld.spark/.SparkDeviceAdminReceiver
```

Do not remove device owner unless intentionally reprovisioning.

## Recovery Root

Normal Android ADB root is disabled. Use recovery for root filesystem changes:

```sh
adb -s CB51286PFD reboot recovery
adb -s CB51286PFD shell id
```

Expected recovery shell:

```text
uid=0(root) ... context=u:r:su:s0
```

Mount partitions:

```sh
adb -s CB51286PFD shell mkdir -p /mnt/system /mnt/userdata
adb -s CB51286PFD shell mount -o rw /dev/block/bootdevice/by-name/system /mnt/system
adb -s CB51286PFD shell mount -o rw /dev/block/bootdevice/by-name/userdata /mnt/userdata
```

## Install Overlays From Recovery

Build overlay APKs outside this repo or with `tools/build-overlays.sh`, then push them in recovery.

Framework/system-bars overlay:

```sh
adb -s CB51286PFD shell mkdir -p /mnt/system/system/product/overlay/SparkSystemBarsOverlay
adb -s CB51286PFD push SparkSystemBarsOverlay.apk /mnt/system/system/product/overlay/SparkSystemBarsOverlay/SparkSystemBarsOverlay.apk
adb -s CB51286PFD shell chmod 0644 /mnt/system/system/product/overlay/SparkSystemBarsOverlay/SparkSystemBarsOverlay.apk
adb -s CB51286PFD shell chown 0:0 /mnt/system/system/product/overlay/SparkSystemBarsOverlay/SparkSystemBarsOverlay.apk
```

SystemUI overlay:

```sh
adb -s CB51286PFD shell mkdir -p /mnt/system/system/product/overlay/SparkSystemUIOverlay
adb -s CB51286PFD push SparkSystemUIOverlay.apk /mnt/system/system/product/overlay/SparkSystemUIOverlay/SparkSystemUIOverlay.apk
adb -s CB51286PFD shell chmod 0644 /mnt/system/system/product/overlay/SparkSystemUIOverlay/SparkSystemUIOverlay.apk
adb -s CB51286PFD shell chown 0:0 /mnt/system/system/product/overlay/SparkSystemUIOverlay/SparkSystemUIOverlay.apk
```

LatinIME overlay:

```sh
adb -s CB51286PFD shell mkdir -p /mnt/system/system/product/overlay/SparkLatinIMEOverlay
adb -s CB51286PFD push SparkLatinIMEOverlay.apk /mnt/system/system/product/overlay/SparkLatinIMEOverlay/SparkLatinIMEOverlay.apk
adb -s CB51286PFD shell chmod 0644 /mnt/system/system/product/overlay/SparkLatinIMEOverlay/SparkLatinIMEOverlay.apk
adb -s CB51286PFD shell chown 0:0 /mnt/system/system/product/overlay/SparkLatinIMEOverlay/SparkLatinIMEOverlay.apk
```

If replacing an existing overlay, remove stale idmap cache:

```sh
adb -s CB51286PFD shell rm -f /mnt/userdata/resource-cache/system@product@overlay@SparkSystemUIOverlay@SparkSystemUIOverlay.apk@idmap
```

Reboot:

```sh
adb -s CB51286PFD reboot
```

## Patch LatinIME Preferences

From recovery after mounting userdata:

```sh
P=/mnt/userdata/user_de/0/com.android.inputmethod.latin/shared_prefs/com.android.inputmethod.latin_preferences.xml
adb -s CB51286PFD shell "sed -i 's#<int name=\"pref_keyboard_theme_20140509\" value=\"[0-9][0-9]*\" />#<string name=\"pref_keyboard_theme_20140509\">5</string>#; s#<string name=\"pref_keyboard_theme_20140509\">[0-9][0-9]*</string>#<string name=\"pref_keyboard_theme_20140509\">5</string>#' $P"
adb -s CB51286PFD shell chown 10126:10126 "$P"
adb -s CB51286PFD shell chmod 0660 "$P"
```

Required preference values:

```xml
<boolean name="pref_key_use_personalized_dicts" value="false" />
<boolean name="pref_key_use_contacts_dict" value="false" />
<boolean name="pref_voice_input_key" value="false" />
<string name="pref_keyboard_theme_20140509">5</string>
```

## Verification

Check app and lock task:

```sh
adb -s CB51286PFD shell dumpsys activity activities | rg -n 'isSleeping|state=|mVisibleRequested|mLockTaskModeState|mKeyguardShowing'
```

Expected:

- `isSleeping=false`
- Spark activity `RESUMED`
- `mVisibleRequested=true`
- `mLockTaskModeState=LOCKED`
- `mKeyguardShowing=false`

Check bars:

```sh
adb -s CB51286PFD shell dumpsys window | rg -n 'mCurrentFocus|NavigationBar|StatusBar|isKeyguardShowing|mStable='
```

Expected:

- focused window is Spark
- no `NavigationBar` window
- `StatusBar` may exist internally but should be invisible
- stable frame covers the whole display

Check overlays:

```sh
adb -s CB51286PFD shell cmd overlay list | rg -n 'spark|Spark|latin|systemui'
```

Expected:

- `[x] com.eviworld.spark.systembars.overlay`
- `[x] com.eviworld.spark.systemui.overlay`
- `[x] com.eviworld.spark.latinime.overlay`

Check keyboard:

```sh
adb -s CB51286PFD shell settings get secure default_input_method
adb -s CB51286PFD shell settings get secure enabled_input_methods
adb -s CB51286PFD shell settings get secure ui_night_mode
adb -s CB51286PFD shell cmd uimode night
adb -s CB51286PFD shell dumpsys uimode | rg -n 'mNightMode=|mNightModeLocked='
```

Expected:

```text
com.android.inputmethod.latin/.LatinIME
```

Expected night-mode values:

- Spark light mode: `ui_night_mode=1`, `Night mode: no`
- Spark dark mode: `ui_night_mode=2`, `Night mode: yes`
- `mNightModeLocked=false`

Check camera power gesture:

```sh
adb -s CB51286PFD shell dumpsys window policy | rg -n 'mDoublePressOnPowerBehavior|mShortPressOnPowerBehavior'
```

Expected:

- short press power sleeps
- double press power is nothing
