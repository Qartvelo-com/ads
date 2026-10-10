---
title: Test mode
description: Debug builds get real ads labelled "Test ad" that are never billed, like AdMob test devices. Force a no-fill to see your AdMob fallback.
---

Never click or watch live (billable) ads in your own app: it generates invalid traffic, which is rejected and can get your app suspended. Test mode lets you see and tap real ads safely.

## Turn it on

**Emulators are always in test mode** (SDK 0.3.4+), like AdMob: the Android Studio emulator, Genymotion and common PC players get test ads and are never billed, even in a release build. The SDK reports the emulator to the backend, which enforces it. The SDK logs `Emulator: test mode is on` at info level.

Since SDK 0.3.3 test mode also turns itself on in **debug builds** (any build where the app is debuggable, such as `./gradlew installDebug`, `npx react-native run-android` or an Expo development build), like AdMob test devices. Release builds are not debuggable, and Google Play rejects debuggable builds, so your users never get test ads. The SDK logs `Debuggable build: test mode is on` at info level when this happens.

On **iOS** (SDK 0.4.0+) the **Simulator and all installations outside the App Store always use test mode**, even with `testMode = false`. This includes Xcode, development, ad hoc, enterprise and TestFlight installations. Live ads require a physical App Store installation with its production receipt present. A missing or sandbox receipt keeps the app in test mode. The legacy `testModeInDebugBuilds` option cannot disable this protection on iOS.

You can also turn it on explicitly, for example for a QA release build:

| Platform | Option |
|---|---|
| Android | `QartveloAdsOptions(testMode = true)` |
| iOS | `options.testMode = true` on `QartveloAdsOptions` |
| React Native | `QartveloAds.initialize({ appKey, testMode: true })` |
| REST API | `"test_mode": true` on `/sdk/initialize` and `/ads/request` |

On Android, opting out with `testModeInDebugBuilds = false` (`testModeInDebugBuilds: false` in React Native) allows live Qartvelo Ads traffic from a physical debug device. The AdMob fallback still uses Google's test units in debuggable builds, so a debug build never requests live AdMob ads, unless you also set `admobTestUnitsInDebugBuilds = false` (`admobTestUnitsInDebugBuilds: false` in React Native). iOS installations outside the App Store cannot opt out; they can still preview eligible real campaign creatives as non-billable test ads.

Test mode is computed from your code and the current installation; it is never cached. Explicit option changes take effect on the next app start, subject to the automatic test-mode protections above.

## What changes

- **Real ads, labelled "Test ad".** For an approved app the backend picks the creative a live request would win, with the same targeting and ranking, so you see the ads your users will see. The SDK shows **Test ad** instead of **Ad** on the badge. No budget is reserved and the advertiser is never charged.
- **Built-in server test ads** when your app is not approved yet, or no live campaign matches the request:

  | Format | Creative |
  |---|---|
  | banner | iOS adaptive: closest-proportion Retina PNG (960x150, 1320x204 or 2184x270). Legacy/Android: widest fitting standard banner; equal widths rotate |
  | interstitial | 1080x1920 PNG |
  | rewarded | 15 s, 720x1280 MP4 |

  SDKs 0.6.0 and later get **animated HTML5 test ads** for banners and interstitials instead
  (`creative_id` `cr_test_banner_html5` and `cr_test_interstitial_html5`), so you can check how HTML5
  ads look and behave in your layout. Rewarded keeps the video test ad, and older SDKs keep the
  images above.

- **iOS works without app registration in test mode.** An empty app key skips session initialization. If a registered app key or bundle ID is rejected, the ad request fails, or there is no fill, SDK 0.6.0 downloads the matching public test creative from `/test-ads/` without an app session. Initialization succeeds in test mode, so you can verify the ad UI before registering the app. These public creatives do not send impression, click, or reward events to the backend. When a valid test session returns a fill, the backend-served ad and its test events work as usual.

- **Backend-served test events** are validated and de-duplicated exactly like live ones, so you can test your event handling, but they are never billed, never earn revenue and never appear in reports. Tapping a backend-served test ad opens the advertiser's page like a live ad would. Public iOS fallback creatives have no click destination and do not send events, and are always the static images and video above.
- The app does not need to be approved yet: test sessions skip the approval check.
- The AdMob adapter replaces your unit ids with Google's public test units (in test mode, and on Android in every debuggable build unless `admobTestUnitsInDebugBuilds` is `false`):

  | Format | Google test unit |
  |---|---|
  | banner | `ca-app-pub-3940256099942544/9214589741` |
  | interstitial | `ca-app-pub-3940256099942544/1033173712` |
  | rewarded | `ca-app-pub-3940256099942544/5224354917` |

## Exercise the fallback

Add `testForceNoFill = true` (`testForceNoFill: true` in React Native). Each load skips the Qartvelo creative and exercises the fallback, so it emits `onFallbackStarted` and then shows a Google test ad. On iOS this works without app registration and does not need a backend request. Google's rewarded test unit grants 10 coins.

## Logs

Set `logLevel = QartveloAdsLogLevel.DEBUG` (`logLevel: 'debug'`) and filter logcat by the tag `QartveloAds`:

```sh
adb logcat -s QartveloAds
```

Debug logs include request timing, fallback decisions and event delivery. Tokens are never logged.

## Sample apps

The [SDK repository](https://github.com/Qartvelo-com/ads) contains a native sample (`android/sample-app`) and a React Native example (`react-native/example`). Both have Load/Show buttons, a banner screen, test-mode and force-no-fill switches, an editable base URL and an on-screen event log.

The iOS QA app is in `ios/sample-app` and uses the SDK from the same checkout. Generate its project with XcodeGen, build and install it in Simulator, then run `qa-run.sh` to capture banner, interstitial and rewarded screenshots and logs. Pass `-forceNoFill` to exercise Google test ads. See the repository README for build commands.

:::caution
Before you publish, make sure release builds use `testMode = false` and `testForceNoFill = false`, and that your release build is not debuggable. Test traffic earns nothing.
:::
