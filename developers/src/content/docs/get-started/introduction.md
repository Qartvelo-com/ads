---
title: Introduction
description: What Qartvelo Ads is, who it is for, and how the pieces fit together.
---

Qartvelo Ads is a direct-sold ad network for Android and iOS apps in Georgia. Local advertisers buy CPM campaigns that run inside publishers' apps as banners, interstitials and rewarded videos. Publishers integrate one SDK and earn a revenue share on every Qartvelo Ads impression, while unfilled requests go to their **own** AdMob account so no inventory is wasted.

```
Your app ──> Qartvelo Ads SDK ──> Qartvelo Ads ad available?
                                   yes │            │ no / timeout / error
                              Qartvelo Ads ad     AdMob adapter ──> your AdMob ad unit
```

## Who this documentation is for

| You are | Start here |
|---|---|
| An Android developer (Kotlin or Java) | [Quickstart](/get-started/quickstart/), then [Android SDK](/android/installation/) |
| An iOS developer (Swift or Objective-C) | [iOS SDK](/ios/installation/) |
| A React Native developer | [React Native installation](/react-native/installation/) |
| Building your own client or debugging traffic | [REST API](/api/overview/) and the [OpenAPI spec](/openapi.yaml) |
| An advertiser or agency | [Campaigns](/advertisers/campaigns/) and [creative specs](/advertisers/creatives/) |
| Using an AI coding assistant | [Build with AI](/ai/overview/) |

## Packages

| Package | Install | Notes |
|---|---|---|
| Android core | `com.qartvelo.ads:core:0.5.1` | Maven Central. API client, caching, rendering, events, banner view |
| Android AdMob adapter | `com.qartvelo.ads:admob:0.5.1` | Optional. Depends on Google's `play-services-ads` |
| iOS core | `QartveloAds` 0.5.1 | Swift Package Manager (`https://github.com/Qartvelo-com/ads`) or CocoaPods from the Git tag |
| iOS AdMob adapter | `QartveloAdsAdMob` 0.5.1 | Optional. Depends on Google's `Google-Mobile-Ads-SDK` 12 |
| React Native | `npm install @qartvelo/react-native-ads@^0.5.1` | Android and iOS; TurboModule and Fabric banner, CocoaPods autolinking on iOS |

Release **0.5.1** is available from the official [npm registry](https://www.npmjs.com/package/@qartvelo/react-native-ads), [Maven Central](https://repo.maven.apache.org/maven2/com/qartvelo/ads/core/) and [GitHub release tags](https://github.com/Qartvelo-com/ads/releases). Third-party indexes such as npm.io may show an older cached version. Check the registry used by installers directly:

```sh
npm view @qartvelo/react-native-ads dist-tags.latest
npm view @qartvelo/ads-mcp dist-tags.latest
```

The SDK source is MIT-licensed at [github.com/Qartvelo-com/ads](https://github.com/Qartvelo-com/ads). The production API is `https://ads.qartvelo.com/`, which is also the SDK's default base URL.

## Key concepts

- **App**: an Android or iOS application registered in the publisher dashboard by its package name (Android) or bundle ID (iOS). Each app gets a public **app key** (`app_` + 24 characters) that you put in your code.
- **Placement**: an ad slot in your app, identified by a short **code** such as `home_banner`, `game_end` or `reward_coins`. A placement has one format: `banner`, `interstitial` or `rewarded`. Your code only ever uses the placement code.
- **Fallback**: when Qartvelo Ads cannot serve a placement (no eligible campaign, timeout, error, kill switch), the SDK shows your AdMob ad unit for that placement instead.
- **Test mode**: a switch in the SDK options that serves built-in test creatives that are never billed, and makes AdMob use Google's public test units.
- **Session**: a short-lived signed token the backend issues at start-up. It carries a random id, never a user or device identifier.

## Platform support

| Platform | Status |
|---|---|
| Android, native (Kotlin/Java) | Supported, minSdk 23 |
| Android, React Native 0.79+ (New Architecture) | Supported, minSdk 24 |
| iOS, native (Swift/Objective-C) | Supported, iOS 13+ |
| iOS, React Native 0.79+ (New Architecture) | Supported since 0.4.1; Swift SDK through a TurboModule and Fabric banner, CocoaPods autolinking. Uses React Native's minimum iOS deployment target (15.1 for RN 0.79) |

## Next steps

- Follow the [Quickstart](/get-started/quickstart/) to show a test ad in about ten minutes.
- Read [How it works](/get-started/how-it-works/) to understand loading, fallback and events.
