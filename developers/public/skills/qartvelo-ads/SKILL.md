---
name: qartvelo-ads
description: Integrate or debug the Qartvelo Ads SDK (Android Kotlin com.qartvelo.ads:core, iOS Swift QartveloAds, React Native @qartvelo/react-native-ads) - banner, interstitial and rewarded ads with automatic fallback to the app's own AdMob units. Use when adding ads to a Georgian Android or iOS app, when code mentions QartveloAds, or when asked about Qartvelo Ads placements, test mode, AdMob fallback or its REST API.
---

# Qartvelo Ads integration

Qartvelo Ads is a direct-sold ad network for Android and iOS apps in Georgia. The SDK serves Qartvelo Ads campaigns first and falls back to the publisher's **own** AdMob ad units. Full docs: https://developers.qartvelo.com (Markdown: https://developers.qartvelo.com/llms-full.txt, any page as `<url>.md`).

## Facts that must be exact

- Version **0.4.1**. Android artifacts on Maven Central: `com.qartvelo.ads:core:0.4.1` (required), `com.qartvelo.ads:admob:0.4.1` (optional AdMob fallback). Repository `mavenCentral()`; versions before 0.3.4 are only on JitPack.
- Kotlin package `com.qartvelo.sdk`, entry point `object QartveloAds`. Options class `QartveloAdsOptions`. Listener `QartveloAdsListener` (all methods have default bodies, main thread).
- `AdSource` is `QARTVELO` or `ADMOB` (React Native: `'qartvelo'` or `'admob'`). There is no `OURADS` value.
- Banner view `com.qartvelo.sdk.QartveloAdsBannerView`, XML attribute **`app:qartvelo_placementId`**.
- iOS (0.4.0+): Swift Package Manager `https://github.com/Qartvelo-com/ads` (products `QartveloAds`, optional `QartveloAdsAdMob`) or pods `QartveloAds` / `QartveloAdsAdMob` `~> 0.4`. iOS 13+. Entry point `QartveloAds` (static methods), `QartveloAdsOptions` (class, set properties), `QartveloAdsDelegate` (optional `@objc` methods), `QartveloAdsBannerView(placementId:)` + `load()`. AdMob adapter: `QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter())` before `initialize`, plus `GADApplicationIdentifier` in Info.plist. The iOS app is its own app in the dashboard (platform iOS, bundle ID) with its own app key.
- React Native: `npm install @qartvelo/react-native-ads`, New Architecture, Android and iOS. Run `pod install` and rebuild iOS; npm includes the Swift SDK and autolinks `RNQartveloAds`. Optional AdMob: `QartveloAds_admobEnabled=true` in Android gradle.properties, or `ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'` in the iOS Podfile plus `GADApplicationIdentifier` in Info.plist. All non-App Store iOS installs always use non-billable test traffic. Rebuild the native app after installing.
- Ads are addressed by **placement code** (`[a-z0-9_]{2,64}`, e.g. `game_end`) created in the publisher dashboard. Each placement has one format: banner, interstitial or rewarded.
- The app key (`app_` + 24 chars) is public and goes in the app. The **SDK secret never goes in an app**.
- Default API `https://ads.qartvelo.com/`; do not set `baseUrl` unless the user runs their own backend.
- The app's `applicationId` must equal the package registered in the dashboard (watch `applicationIdSuffix`), otherwise `package_mismatch`.

## Steps

1. Ask for (or find) the app key and the placement codes and formats. If missing, tell the user to create them at https://ads.qartvelo.com (Apps -> app -> placements).
2. Add the dependencies:
   - Android: `mavenCentral()` in `settings.gradle.kts` `dependencyResolutionManagement.repositories` (usually already there), then `implementation("com.qartvelo.ads:core:0.4.1")` and, for fallback, `implementation("com.qartvelo.ads:admob:0.4.1")`.
   - iOS: add the Swift package (or pods) above.
   - React Native: install `@qartvelo/react-native-ads`. Android resolves its SDK from Maven Central; enable fallback with `QartveloAds_admobEnabled=true`. On iOS run `pod install` (the npm package includes the Swift SDK); enable fallback with `ENV['QARTVELO_ADS_ADMOB_ENABLED'] = 'true'` in the Podfile and the iOS AdMob App ID in Info.plist. Rebuild the app.
3. If the AdMob adapter is used, make sure the manifest has the app's own AdMob App ID: `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID" android:value="ca-app-pub-...~..."/>` (missing -> crash at start-up). Keep any existing AdMob setup.
4. Initialize once at start-up with test mode in debug builds:

   ```kotlin
   class MyApp : Application() {
       override fun onCreate() {
           super.onCreate()
           QartveloAds.initialize(this, "app_xxx", QartveloAdsOptions(testMode = BuildConfig.DEBUG))
       }
   }
   ```

   ```swift
   // AppDelegate.application(_:didFinishLaunchingWithOptions:)
   QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter()) // only with QartveloAdsAdMob
   QartveloAds.initialize(appKey: "app_xxx")
   ```

   ```tsx
   QartveloAds.initialize({ appKey: 'app_xxx', testMode: __DEV__ }).catch(() => {});
   ```

5. Add each placement:
   - Banner: `QartveloAdsBannerView` with `app:qartvelo_placementId="code"`, call `load()`, call `destroy()` in `onDestroy`. RN: `<QartveloAdsBanner placementId="code" style={{ width: '100%' }} />`.
   - Interstitial: `QartveloAds.loadInterstitial("code")` early; `showInterstitial(activity, "code", listener)` at a natural break. Continue the flow from `onDismissed`, `onNoAdAvailable` **and** `onLoadFailed`. RN: `await loadInterstitial`, `await showInterstitial` (resolves on dismiss or `{ shown: false }`).
   - iOS: `QartveloAdsBannerView(placementId:)` + `load()`; `QartveloAds.loadInterstitial("code", delegate: self)` then `showInterstitial("code", from: viewController, delegate: self)`.
   - Rewarded: `loadRewarded`, then `showRewarded`; grant the reward **only** in `onReward` (Swift: `qartveloAd(_:didEarnReward:)`, RN: `result.rewarded`), exactly once. Never grant from `onDismissed`.
6. Reload after each full-screen show for the next opportunity.
7. Verify: debug builds are in test mode automatically (SDK 0.3.3+; emulators always, SDK 0.3.4+): ads carry a "Test ad" label (real creatives for approved apps, purple "TEST AD" placeholders otherwise) and are never billed; `testForceNoFill = true` shows the AdMob fallback (Google test ads). Logs: `adb logcat -s QartveloAds` with `logLevel = QartveloAdsLogLevel.DEBUG`.

## Rules

- Never click or watch live ads in development; use test mode. Release builds must have `testMode = false` and `testForceNoFill = false`.
- Do not wrap SDK calls in try/catch for exceptions: the SDK never throws; errors arrive through callbacks / rejected promises.
- Do not call `initialize` more than once or with different options; it is idempotent per process.
- Do not parse, log or store session or impression tokens.
- One visible banner per placement code at a time.
- Do not invent APIs. If unsure, read the page: e.g. https://developers.qartvelo.com/android/api-reference.md, https://developers.qartvelo.com/ios/api-reference.md or https://developers.qartvelo.com/react-native/api-reference.md

## Error codes

`NOT_INITIALIZED`, `INVALID_PLACEMENT`, `NETWORK_ERROR`, `TIMEOUT`, `NO_FILL`, `CREATIVE_FAILED`, `AD_EXPIRED`, `SHOW_FAILED`, `ALREADY_SHOWING`, `INTERNAL_ERROR` (RN lowercase, plus `unsupported_platform`, `module_unavailable`, `invalid_argument`). Fallback reasons: `no_fill`, `timeout`, `error`, `creative_failed`, `disabled`.

## Release checklist

App and placements approved; package matches; test mode off in release; own AdMob App ID and unit ids (no Google test ids); consent passed with `QartveloAds.setPrivacy`; `logLevel` ERROR or NONE; R8 build tested; banners destroyed.
