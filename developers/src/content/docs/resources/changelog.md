---
title: Changelog
description: Release history of the Qartvelo Ads SDKs.
---

Releases are tagged in [Qartvelo-com/ads](https://github.com/Qartvelo-com/ads). The Android SDK, the iOS SDK, the AdMob adapters and the React Native plugin share one version number.

## 0.5.0

- **One-place AdMob setup for React Native**: an Expo config plugin
  (`["@qartvelo/react-native-ads", { "admob": { ... } }]`) and, for bare React Native, the same object
  under `"@qartvelo/react-native-ads"` in `app.json`. Both enable the adapter, write the App IDs and
  Google's SKAdNetwork list, and validate the App IDs. The 0.4.x flags keep working.
- **Per-platform options**: `appKey` and `admobAdUnits` values accept `{ android, ios }`.
- **`preload` and `loadIfNeeded`** in the React Native API.
- **Setup issues**: a wrong package or platform for the app key, and placement codes the dashboard
  does not have, are reported once (`onSetupIssue` on Android, `qartveloAdsDidReportSetupIssue` on
  iOS, the `setupIssue` event and a development warning in React Native). Setup issues work without
  the backend's new `error.details`; with it, the messages also name the registered package and platform.
- **TypeScript**: code that switches exhaustively on `QartveloAdsEvent` types must handle the new
  `setupIssue` event.
- **AdMob test units in debug builds**: `admobTestUnitsInDebugBuilds` (default true) on Android.
  Native Android debug builds now use Google's test units for the AdMob fallback by default, even with
  test mode off; set `admobTestUnitsInDebugBuilds = false` to restore the old behaviour (your real
  AdMob units from a debug build). The option is accepted and ignored on iOS.
- **Packaging**: `com.qartvelo.ads:admob` brings `play-services-ads` at runtime scope, so React Native
  0.83 (Kotlin 2.1) builds with the adapter; the React Native module no longer adds JitPack and
  GitHub Packages for 0.3.4 and later. Apps that call Google's AdMob API directly must declare
  play-services-ads themselves; the adapter no longer exposes it at compile time.
- **Android AdMob fallback banner size**: the adapter now requests Google's standard anchored
  adaptive banner, like iOS and as documented. It requested the large anchored variant, which is
  about twice as tall on phones (128 dp instead of 64 dp at 411 dp width).

## 0.4.1

- **React Native iOS support**: the same JavaScript API now bridges the Swift SDK through a
  TurboModule and Fabric adaptive banner. Initialization, interstitials, rewarded results,
  readiness, privacy and lifecycle events are supported. Simulator and non-App Store installs
  keep the native SDK's non-billable test mode, even with `testMode: false`.
- **Simple iOS installation**: the npm package includes the canonical Swift SDK sources and
  privacy manifest. CocoaPods autolinks `RNQartveloAds`; no separate Qartvelo pod publication or
  Swift Package Manager dependency is needed. Enable the optional AdMob adapter with
  `ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'` in the Podfile and set the iOS AdMob App ID.
- **React Native example cleanup**: starts automatically in test mode, with banner Hide/Show,
  interstitial and rewarded Load/Show buttons and an optional event log. Configuration lives in
  `src/AppConfig.ts`; the backend URL input and debug controls were removed.
- CI builds the React Native iOS example with and without AdMob. Developer docs, AI skill and
  MCP integration guidance now cover React Native iOS.
- Native Android and iOS SDKs: shared version bump; ad-serving behavior is unchanged.

## 0.4.0

- **iOS SDK**: `QartveloAds` and the optional `QartveloAdsAdMob` adapter (Google Mobile Ads 12) for
  iOS 13+, with Swift Package Manager (`https://github.com/Qartvelo-com/ads`) and CocoaPods. Banner
  view, interstitial and rewarded ads, the same remote configuration, fallback, test mode and event
  rules as Android. The Simulator and all non-App Store installations (including TestFlight) always
  use test mode, even when `testMode = false`; the legacy `testModeInDebugBuilds` option is ignored.
  Only physical App Store installations with a production receipt present may serve live traffic.
  When registration or fill is unavailable in test mode, iOS fetches
  public server test creatives without requiring a valid app session. See [iOS installation](/ios/installation/).
- **iOS adaptive banners**: compact 50–90 point heights, using the AdMob adapter's standard size
  calculation when linked. Adaptive requests select horizontal creatives by slot proportions;
  inline rectangles use `usesAdaptiveSize = false`. Public test banners have Retina artwork at
  960x150, 1320x204 and 2184x270 pixels.
- Backend: apps have a platform (Android or iOS, bundle ID for iOS), an app key only works on its
  own platform (`platform_mismatch`), and campaigns can target platforms.
- Android and React Native: no changes besides the version number.

## 0.3.4

- **Maven Central**: `com.qartvelo.ads:core` and `com.qartvelo.ads:admob` are published to Maven
  Central, so `mavenCentral()` is all you need; the JitPack repository line can be removed.
  JitPack and GitHub Packages keep working. The artifacts now include javadoc jars and are
  GPG-signed. React Native and Expo apps need no extra repository.
- **Emulators are always in test mode**, like AdMob test devices: ads are labelled "Test ad" and
  never billed, even in release builds. The SDK reports `is_emulator` and the backend enforces it.

## 0.3.3

- Test mode turns itself on in debuggable (developer) builds, like AdMob test devices. Release
  builds are unaffected. Opt out with `testModeInDebugBuilds = false` (Android) or
  `testModeInDebugBuilds: false` (React Native).
- Test ads are labelled **Test ad** instead of **Ad** on banners, interstitials and rewarded videos.
- Backend: in test mode an approved app now gets the real creative a live request would win,
  flagged `"test": true` and never billed, instead of the built-in placeholder (which is still
  used for unapproved apps and when nothing matches).

## 0.3.2

- The "Ad" badge on Qartvelo Ads banners, interstitials and rewarded videos is now a link: tapping
  it opens the Qartvelo Ads website with `?ref=<app package name>`. It is not an ad click: no click
  event is sent, `onClicked` is not called and the advertiser's page is not opened. AdMob fallback
  ads are unchanged.

## 0.3.1

- React Native plugin: fixed the Android build on React Native versions before 0.87, where
  `QartveloAdsBannerHostView` failed to compile because it used an event-dispatcher overload that
  only newer React Native versions have. Apps that patched `node_modules` for this can drop the
  patch.
- Android SDK, AdMob adapter and MCP server: version bump only, no functional changes.

## 0.3.0

- Short Maven coordinates: `com.qartvelo.ads:core` and `com.qartvelo.ads:admob`, identical on JitPack, GitHub Packages and `mavenLocal()`.
- Repository moved to `Qartvelo-com/ads`.
- The React Native plugin depends on `com.qartvelo.ads:core`; the group-switch Gradle property was removed. `QartveloAds_sdkVersion` still pins the native version.
- Native sample renamed to `com.qartvelo.sample`.

## 0.2.0

First public release.

- Android core SDK (`com.qartvelo.sdk`): banner, interstitial and rewarded ads, creative caching, remote configuration with offline cache, ordered event delivery with retries.
- Optional AdMob fallback adapter with parallel preloading and automatic discovery.
- React Native plugin `@qartvelo/react-native-ads`: TurboModule and Fabric banner on the New Architecture.
- Native sample app and React Native example app.
