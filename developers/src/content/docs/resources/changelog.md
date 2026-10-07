---
title: Changelog
description: Release history of the Qartvelo Ads SDKs.
---

Releases are tagged in [Qartvelo-com/ads](https://github.com/Qartvelo-com/ads). The Android SDK, the AdMob adapter and the React Native plugin share one version number.

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
