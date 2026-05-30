# Requirements

## Product Goal

Build a fully branded Spark AI education tablet that behaves like a dedicated appliance, not a general Android tablet.

The first usable screen must be Spark, not a launcher, browser chooser, settings screen, or Android onboarding flow.

## App Experience

- Launch `https://spark.eviworld.com/` automatically.
- If Spark starts before network is ready, show a large friendly offline screen with Reload. Retry loading automatically when connectivity appears, but only until the first successful page load.
- Future offline recovery should add a controlled `Connect Wi-Fi` flow that uses Android's Wi-Fi and captive portal UI while keeping the rest of Settings unreachable.
- Use a native Android shell around WebView, not a full browser UI.
- Keep rendering fast enough for an education UI with text, chat, lessons, worksheets, occasional images, and camera/file uploads.
- Keep the Spark web app free to use modern browser APIs where WebView supports them.
- Support camera/photo attachment from the web app through Android file chooser and camera intents.
- Reload button must be available from the Spark system menu.

## Branding

- Replace user-facing Sony/Xperia branding where practical.
- Use Spark AI boot/app branding.
- Spark lock screen text is exactly:
  - `Spark AI`
  - `Swipe up to unlock`
- Do not show Android/TWRP/recovery UI during normal boot. Recovery UI is acceptable only when intentionally booted to recovery.

## Kiosk Restrictions

- Spark app is the home activity.
- Spark app runs in Android lock task mode.
- Users should not freely browse, launch other apps, or change Android system settings.
- Settings, browser, launcher, and other non-Spark apps should be hidden or unreachable where practical.
- Native bottom navigation must not appear on bottom swipe.
- Native notification/status UI must not be the user-facing control surface.
- Device owner should disable native status bar access and keyguard.
- Double-press power must not open Camera.

## Spark-Owned System UI

- Spark draws its own top bar.
- Top bar shows:
  - clock
  - date
  - Wi-Fi state with standard-looking Wi-Fi icon
  - battery/charging state with standard-looking battery icon
  - Spark AI label
- Top bar height should match the native Android status bar height measured on this tablet: 24 dp / 36 px at 240 dpi.
- Top bar uses native/default Android font sizing, not oversized custom chrome.
- Wi-Fi and battery icons must fit the bar without clipping.
- Top swipe opens Spark's own centered system menu.
- Spark system menu contains:
  - brightness
  - auto rotate / rotation lock
  - light/dark mode
  - Reload
- Brightness uses a slider with standard Android-style brightness iconography, not a text label.
- Auto rotate / rotation lock, light/dark mode, and Reload use standard vector iconography.
- Spark system menu is a centered top drawer, not a full-width dialog.
- Spark system menu has a bottom gripper and closes only by tapping outside it or using the gripper. Dragging the brightness slider must not close the drawer.
- Spark system menu follows the current light/dark mode. It must not stay visually dark in light mode.
- Spark system menu must not have a visible close X or large close button.

## Locking

- Physical power button is the transport lock action.
- Screen auto-locks and powers down after 2 minutes of inactivity.
- Long-press power should show the Android shutdown confirmation, then actually shut down when confirmed.
- No PIN/password is needed.
- On screen-off, Spark stores a locked state.
- On wake, Spark shows its own lock overlay.
- Swipe-up unlock should behave like Android keyguard:
  - drag moves the lock overlay with the finger
  - release before threshold animates back
  - release past threshold unlocks
- Lock screen must be rotation-aware:
  - if auto-rotate is locked in portrait, the lock screen remains portrait
  - unlocking should return to the same locked orientation
  - same applies to landscape/reverse orientations

## Keyboard

- Use stock/customized Android LatinIME, not a custom hand-written keyboard.
- Keyboard language should be English UK.
- Keyboard should keep native touch feedback, shift state, delete behavior, correction, suggestions, and layout quality.
- Voice/mic key should be removed.
- Contact-name suggestion prompt should be removed by disabling contact/personalized dictionaries.
- Suggestions and autocorrect are allowed.
- Keyboard theme should follow the Spark light/dark mode switch.

## OS/Platform

- Current base OS is LineageOS 18.1 / Android 11 for `castor_windy`.
- Keep the old Sony kernel and device drivers from the ROM/device tree.
- Use the modern WebView installed on the device.
- Do not attempt to replace the full Linux networking stack independently of Android userspace/kernel without a full ROM/kernel project.

## Repository Policy

- Store app source, overlays, scripts, generated branding source assets, and docs in `~/spark-os`.
- Do not store ROM zips, partition images, full system images, pulled system APKs, or signing secrets in `~/spark-os`.
