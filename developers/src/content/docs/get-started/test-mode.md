---
title: Test mode
description: Debug builds get real ads labelled "Test ad" that are never billed, like AdMob test devices. Force a no-fill to see your AdMob fallback.
---

Never click or watch live (billable) ads in your own app: it generates invalid traffic, which is rejected and can get your app suspended. Test mode lets you see and tap real ads safely.

## Turn it on

**Emulators are always in test mode** (SDK 0.3.4+), like AdMob: the Android Studio emulator, Genymotion and common PC players get test ads and are never billed, even in a release build. The SDK reports the emulator to the backend, which enforces it. The SDK logs `Emulator: test mode is on` at info level.

Since SDK 0.3.3 test mode also turns itself on in **debug builds** (any build where the app is debuggable, such as `./gradlew installDebug`, `npx react-native run-android` or an Expo development build), like AdMob test devices. Release builds are not debuggable, and Google Play rejects debuggable builds, so your users never get test ads. The SDK logs `Debuggable build: test mode is on` at info level when this happens.

You can also turn it on explicitly, for example for a QA release build:

| Platform | Option |
|---|---|
| Android | `QartveloAdsOptions(testMode = true)` |
| React Native | `QartveloAds.initialize({ appKey, testMode: true })` |
| REST API | `"test_mode": true` on `/sdk/initialize` and `/ads/request` |

To see exactly what a release build does from a debug build (live, billable ads: don't tap them), opt out with `testModeInDebugBuilds = false` (`testModeInDebugBuilds: false` in React Native).

Test mode is read only from your code and the build; it is never cached, so turning it off takes effect on the next app start.

## What changes

- **Real ads, labelled "Test ad".** For an approved app the backend picks the creative a live request would win, with the same targeting and ranking, so you see the ads your users will see. The SDK shows **Test ad** instead of **Ad** on the badge. No budget is reserved and the advertiser is never charged.
- **Built-in test ads** when your app is not approved yet, or no live campaign matches the request:

  | Format | Creative |
  |---|---|
  | banner | 320x50 PNG |
  | interstitial | 1080x1920 PNG |
  | rewarded | 15 s, 720x1280 MP4 |

- Impressions, clicks and rewards are validated and de-duplicated exactly like live ones, so you can test your event handling, but they are never billed, never earn revenue and never appear in reports. Tapping a test ad opens the advertiser's page like a live ad would.
- The app does not need to be approved yet: test sessions skip the approval check.
- The AdMob adapter replaces your unit ids with Google's public test units:

  | Format | Google test unit |
  |---|---|
  | banner | `ca-app-pub-3940256099942544/9214589741` |
  | interstitial | `ca-app-pub-3940256099942544/1033173712` |
  | rewarded | `ca-app-pub-3940256099942544/5224354917` |

## Exercise the fallback

Add `testForceNoFill = true` (`testForceNoFill: true` in React Native). Every Qartvelo Ads request answers `no_fill` with reason `test_no_fill`, so each load emits `onFallbackStarted` and then shows a Google test ad. Google's rewarded test unit grants 10 coins.

## Logs

Set `logLevel = QartveloAdsLogLevel.DEBUG` (`logLevel: 'debug'`) and filter logcat by the tag `QartveloAds`:

```sh
adb logcat -s QartveloAds
```

Debug logs include request timing, fallback decisions and event delivery. Tokens are never logged.

## Sample apps

The [SDK repository](https://github.com/Qartvelo-com/ads) contains a native sample (`android/sample-app`) and a React Native example (`react-native/example`). Both have Load/Show buttons, a banner screen, test-mode and force-no-fill switches, an editable base URL and an on-screen event log.

:::caution
Before you publish, make sure release builds use `testMode = false` and `testForceNoFill = false`, and that your release build is not debuggable. Test traffic earns nothing.
:::
