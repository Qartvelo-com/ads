---
title: Troubleshooting
description: Fixes for common React Native build and runtime problems.
---

| Symptom | Fix |
|---|---|
| `Could not find com.qartvelo.ads:core:0.4.0` | Make sure `mavenCentral()` is in `allprojects.repositories` in `android/build.gradle`; versions before 0.4.0 also need JitPack ([Installation](/react-native/installation/#2-native-sdk-repository)) |
| App crashes at start: "The Google Mobile Ads SDK was initialized incorrectly" | `QartveloAds_admobEnabled=true` without the AdMob `APPLICATION_ID` meta-data. Add it, or disable the adapter |
| Promises reject with `module_unavailable` | The app binary predates the package. Rebuild with `npx react-native run-android` |
| Promises reject with `unsupported_platform` | Running on web or another unsupported platform. Guard with `QartveloAds.isSupported()` |
| `initialize` rejects with `network_error` on the emulator (local backend) | Use `http://10.0.2.2:<port>/`, not `localhost`, and allow cleartext for it in debug |
| `initialize` rejects with `not_initialized` | The backend rejected the app key: check the key, that the package name equals your `applicationId`, and that the app is approved (or use `testMode`) |
| Options seem ignored | Initialization happens once per process; force-stop and restart the app after changing options |
| Every load ends with `fallbackStarted` reason `timeout` | The backend answered after the request timeout. Common on slow debug networks; raise the placement timeout in the dashboard while testing |
| `fallbackStarted` but no AdMob ad | `QartveloAds_admobEnabled` is not set, `admobFallback` is false, the placement's fallback is `none`, or no AdMob unit is configured for it |
| Banner stays empty | Check `onLoadFailed`; give the component a width (it defaults to `100%`) and make sure only one banner per placement code is visible |
| Two copies of React in Metro (local package) | Point Metro's `watchFolders` at the package and block its `node_modules/react(-native)`, as in the example app's `metro.config.js` |

More in the general [Troubleshooting](/resources/troubleshooting/) page.
