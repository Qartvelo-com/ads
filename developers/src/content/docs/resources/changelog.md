---
title: Changelog
description: Release history of the Qartvelo Ads SDKs.
---

Releases are tagged in [Qartvelo-com/ads](https://github.com/Qartvelo-com/ads). The Android SDK, the AdMob adapter and the React Native plugin share one version number.

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
