# Wi-Fi Connectivity Approach

## Summary

Allowing users to connect Spark tablets to Wi-Fi is doable, including captive portal networks, but there are two very different implementation levels.

The recommended first implementation is a controlled Spark `Connect Wi-Fi` flow that temporarily hands off to Android's own Wi-Fi and captive portal UI. A fully branded Spark-native Wi-Fi UI is possible only with more platform work and higher risk.

## Recommended Path

Add a controlled Spark `Connect Wi-Fi` action.

Entry points:

- Show `Connect Wi-Fi` on the Spark offline screen.
- Optionally expose the same action behind a hidden or admin-only gesture in the Spark controls drawer.
- Keep Android Settings unreachable during normal kiosk use.

When the flow starts:

- Temporarily unhide/enable `com.android.settings` if Spark has hidden it through device-owner policy.
- Add `com.android.settings` and `com.android.captiveportallogin` to the lock-task package allowlist.
- Launch Android's Wi-Fi settings or Wi-Fi panel.
- Let Android handle SSID selection, saved networks, passwords, enterprise Wi-Fi, and network validation.
- Let Android launch `CaptivePortalLogin` when the selected network needs browser-based sign-in.
- Watch network state with `ConnectivityManager`.
- Return to Spark automatically once the active network is usable and validated.
- Re-hide Android Settings after returning to Spark, unless the device is still in an explicit admin/maintenance flow.

This path should keep the user-facing kiosk tight while using Android's battle-tested networking UX for the parts that are genuinely system-level.

## Why Android Should Own The First Version

Captive portals are messy in ways that are easy to get subtly wrong:

- The login page must load over the not-yet-validated Wi-Fi network.
- Android must re-check network validation after login.
- Hotel, school, airport, and device-enrollment portals often depend on Android's built-in captive portal flow.
- Enterprise Wi-Fi can involve EAP methods, certificates, identity fields, saved credentials, and device policy behavior.
- Android already has the right saved-network, password, and retry behavior.

Using Android's Wi-Fi UI also avoids building and maintaining a partial Settings clone inside Spark.

## Implementation Shape

Spark currently hides `com.android.settings` and keeps only Spark plus file/camera helper packages in the lock-task allowlist. The Wi-Fi setup flow should make those policies temporarily broader only while setup is active.

App changes:

- Add a `Connect Wi-Fi` button to the offline page.
- Add an optional hidden/admin drawer entry for Wi-Fi setup.
- Add `com.android.settings` and `com.android.captiveportallogin` to package visibility queries if package resolution needs it.
- Extend lock-task package resolution so Wi-Fi setup can include:
  - `com.eviworld.spark`
  - `com.android.settings`
  - `com.android.captiveportallogin`
  - existing document/camera helper packages
- During Wi-Fi setup, call `setApplicationHidden(admin, "com.android.settings", false)`.
- Launch Wi-Fi UI with Android Settings intents, preferring the most constrained Wi-Fi entry point available on the ROM.
- Monitor `NetworkCapabilities`.
- Treat `NET_CAPABILITY_VALIDATED` as the successful return signal. `NET_CAPABILITY_INTERNET` alone can be present before captive portal login has completed.
- Bring Spark back to the foreground when validation succeeds.
- Re-apply the normal kiosk policy after the flow exits.

Operational checks:

```sh
adb -s CB51286PFD shell pm list packages | rg 'com.android.settings|com.android.captiveportallogin'
adb -s CB51286PFD shell dumpsys connectivity | rg -n 'VALIDATED|CAPTIVE_PORTAL|TRANSPORT_WIFI'
adb -s CB51286PFD shell dumpsys device_policy | rg -n 'lockTask|com.android.settings|com.android.captiveportallogin'
```

## Kiosk Guardrails

The Wi-Fi flow should not become a general Settings escape hatch.

Guardrails:

- Show it only when offline, or behind an admin-only gesture.
- Launch directly into Wi-Fi settings/panel, not the Settings root.
- Keep status bar and navigation restrictions active.
- Do not enable broad lock-task features.
- Automatically return to Spark after validated connectivity.
- Provide a visible cancel/back-to-Spark path.
- Re-hide Settings when the flow finishes.

Some Settings subpages may still be reachable from Android's Wi-Fi screen. That is the tradeoff of using the pragmatic Android-managed path. If that surface is unacceptable after testing, the next option is the more expensive Spark-native implementation below.

## More Branded But Harder Path

A Spark-native Wi-Fi UI would require Spark to own more of the network provisioning experience:

- Scan networks with `WifiManager`.
- Request and handle location permission and location-services requirements for scans.
- Render SSID lists, security state, signal strength, saved networks, and retry/error states.
- Build password and enterprise credential UI.
- Handle modern Android restrictions around app-driven Wi-Fi connection.
- Use privileged/system-app permissions, device-owner capabilities, or ROM changes for persistent network configuration where public APIs are not enough.
- Detect captive portal state with `ConnectivityManager`.
- Launch or embed a captive portal browser that is bound to the unvalidated Wi-Fi network.
- Hand captive portal completion back to Android's network validation path.
- Correctly handle forget network, wrong password, reconnect, no-internet networks, and enterprise certificate flows.

This is feasible because Spark controls the ROM-ish environment, but it is much more work and easier to get wrong. It should be treated as a second phase only if the Android-managed flow is too unbranded or too broad for production.

## Recommendation

Implement the Android-managed `Connect Wi-Fi` flow first. It gives Spark a controlled offline recovery path while relying on Android for SSID selection, credentials, saved networks, enterprise Wi-Fi, captive portal sign-in, and validation.

Only build a fully branded Spark-native Wi-Fi UI after testing proves the Settings-based flow is unacceptable.
