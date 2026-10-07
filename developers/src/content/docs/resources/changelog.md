---
title: Changelog
description: Release history of the Qartvelo Ads SDKs.
---

Releases are tagged in [Qartvelo-com/ads](https://github.com/Qartvelo-com/ads). The Android SDK, the AdMob adapter and the React Native plugin share one version number.

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
