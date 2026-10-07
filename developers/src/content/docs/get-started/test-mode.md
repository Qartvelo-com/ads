---
title: Test mode
description: Develop and QA with non-billable test ads, and force a no-fill to see your AdMob fallback.
---

Never click or watch live ads in your own app: it generates invalid traffic, which is rejected and can get your app suspended. Use test mode for every debug and QA build.

## Turn it on

| Platform | Option |
|---|---|
| Android | `QartveloAdsOptions(testMode = BuildConfig.DEBUG)` |
| React Native | `QartveloAds.initialize({ appKey, testMode: __DEV__ })` |
| REST API | `"test_mode": true` on `/sdk/initialize` and `/ads/request` |

Test mode is read only from your code; it is never cached, so turning it off takes effect on the next app start.

## What changes

- The backend never selects live campaigns and never reserves budget. It returns built-in creatives labelled **TEST AD**:

  | Format | Creative |
  |---|---|
  | banner | 320x50 PNG |
  | interstitial | 1080x1920 PNG |
  | rewarded | 15 s, 720x1280 MP4 |

- Impressions, clicks and rewards are validated and de-duplicated exactly like live ones, so you can test your event handling, but they are never billed, never earn revenue and never appear in reports.
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
Before you publish, make sure release builds use `testMode = false` and `testForceNoFill = false`. Test traffic earns nothing.
:::
