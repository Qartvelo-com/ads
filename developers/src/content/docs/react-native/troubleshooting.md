---
title: Troubleshooting
description: Fixes for common React Native build and runtime problems.
---

| Symptom | Fix |
|---|---|
| `Could not find com.qartvelo.ads:core:0.5.0` | Make sure `mavenCentral()` is in `allprojects.repositories` in `android/build.gradle`; versions before 0.3.4 also need JitPack ([Installation](/react-native/installation/#2-native-sdk-repository-android)) |
| App crashes at start: "The Google Mobile Ads SDK was initialized incorrectly" | The adapter is on but the AdMob `APPLICATION_ID` meta-data is missing (legacy `QartveloAds_admobEnabled=true` setup). Add it, or set `admob.androidAppId` in the Expo plugin entry or the `app.json` key, which writes it for you ([Installation](/react-native/installation/#3-admob-fallback-optional)) |
| Promises reject with `module_unavailable` | The app binary predates the package. Rebuild with `npx react-native run-android` |
| Promises reject with `unsupported_platform` | Running on web or another unsupported platform. Guard with `QartveloAds.isSupported()` |
| `initialize` rejects with `network_error` on the emulator (local backend) | Use `http://10.0.2.2:<port>/`, not `localhost`, and allow cleartext for it in debug |
| `initialize` rejects with `not_initialized` | The backend rejected the app key: check the key, that the package name equals your `applicationId`, and that the app is approved (or use `testMode`) |
| Options seem ignored | Initialization happens once per process; force-stop and restart the app after changing options |
| Every load ends with `fallbackStarted` reason `timeout` | The backend answered after the request timeout. Common on slow debug networks; raise the placement timeout in the dashboard while testing |
| `fallbackStarted` but no AdMob ad | The `admob` config is missing from the Expo plugin entry or `app.json` (or the legacy `QartveloAds_admobEnabled` flag is unset), `admobFallback` is false, the placement's fallback is `none`, or no AdMob unit is configured for it |
| Banner stays empty | Check `onLoadFailed`; give the component a width (it defaults to `100%`) and make sure only one banner per placement code is visible |
| Two copies of React in Metro (local package) | Point Metro's `watchFolders` at the package and block its `node_modules/react(-native)`, as in the example app's `metro.config.js` |

## Setup warnings

| Warning code | Meaning | Fix |
|---|---|---|
| `package_mismatch` | The app key is registered for another package name or bundle ID | Use the key of the app registered for this package, or correct the package in the dashboard |
| `platform_mismatch` | The app key belongs to the app of the other platform | Register an app per platform and pass `appKey: { android, ios }` |
| `unknown_placement` | The placement code does not exist for this app | Create it in the dashboard with the format named in the warning |
| `format_mismatch` | The placement exists with another format | Use a placement of the right format, or change its format |
| Build error naming `admob.androidAppId` or `admob.iosAppId` | The AdMob App ID is missing or not an App ID (it must contain `~`) | Copy the App ID from AdMob, not an ad unit id (`/`) |
| Build error "in an Expo project" | A top-level `"@qartvelo/react-native-ads"` key in an Expo `app.json` | Move it into the plugin entry in `expo.plugins` |
| Build error "already sets ... AdMob App ID" | The app already declares a different AdMob App ID | Keep one App ID, in the Qartvelo config |

More in the general [Troubleshooting](/resources/troubleshooting/) page.
