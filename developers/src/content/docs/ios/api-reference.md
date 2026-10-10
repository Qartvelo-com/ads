---
title: API reference
description: Complete public API of the Qartvelo Ads iOS SDK 0.6.0 (modules QartveloAds and QartveloAdsAdMob).
---

Module `QartveloAds`, version `0.6.0` (`QartveloAds.sdkVersion`). Every method is safe to call from any thread, never throws, and delivers callbacks on the main thread. All types are available to Objective-C except `QartveloAdsPrivacy` and the fallback adapter protocols.

## QartveloAds

```swift
final class QartveloAds: NSObject {
    static let sdkVersion: String // "0.6.0"

    static func initialize(appKey: String, options: QartveloAdsOptions = QartveloAdsOptions(),
                           completion: ((Bool, QartveloAdsError?) -> Void)? = nil)
    static var isInitialized: Bool { get }

    static func loadInterstitial(_ placementId: String, delegate: QartveloAdsDelegate? = nil)
    static func showInterstitial(_ placementId: String, from viewController: UIViewController, delegate: QartveloAdsDelegate? = nil)
    static func isInterstitialReady(_ placementId: String) -> Bool // main thread

    static func loadRewarded(_ placementId: String, delegate: QartveloAdsDelegate? = nil)
    static func showRewarded(_ placementId: String, from viewController: UIViewController, delegate: QartveloAdsDelegate? = nil)
    static func isRewardedReady(_ placementId: String) -> Bool // main thread

    static func addObserver(_ observer: QartveloAdsDelegate)    // all placements, held weakly
    static func removeObserver(_ observer: QartveloAdsDelegate)
    static func setLogLevel(_ level: QartveloAdsLogLevel)
    static func setPrivacy(_ privacy: QartveloAdsPrivacy)
    static func registerFallbackAdapter(_ adapter: QartveloFallbackAdapter)
}
```

Per-call delegates are held only until the load or show ends; global observers are held weakly.

## QartveloAdsBannerView

```swift
final class QartveloAdsBannerView: UIView {
    convenience init(placementId: String)
    var placementId: String?
    weak var delegate: QartveloAdsDelegate?
    weak var rootViewController: UIViewController? // for AdMob click-through; defaults to the nearest view controller
    var usesAdaptiveSize: Bool              // default true: anchored adaptive slot; false: the creative's size
    var sizing: QartveloBannerSizing        // default .anchored; .inline for banners in scrolling content (wins over usesAdaptiveSize)
    var inlineMaxHeight: CGFloat            // inline only: the most the banner may be tall, in points, default 250, at least 32
    func load()    // idempotent
    func destroy()
}

@objc enum QartveloBannerSizing: Int { case anchored, inline }
```

Set `sizing` and `inlineMaxHeight` before `load()`. `.anchored`: Google's anchored adaptive slot, full width and 50 to 90 points tall. `.inline`: the ad takes the biggest size that fits the width and `inlineMaxHeight` keeping its proportions, and the view is as tall as that ad. See [Inline banners](/ios/banner/#inline-banners).

## QartveloAdsDelegate

Every method is optional.

```swift
@objc protocol QartveloAdsDelegate: AnyObject {
    optional func qartveloAdDidLoad(_ info: QartveloAdsAdInfo)
    optional func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError)
    optional func qartveloAdDidShow(_ info: QartveloAdsAdInfo)
    optional func qartveloAdDidRecordImpression(_ info: QartveloAdsAdInfo)
    optional func qartveloAdDidClick(_ info: QartveloAdsAdInfo)
    optional func qartveloAdDidDismiss(_ info: QartveloAdsAdInfo)
    optional func qartveloAd(_ info: QartveloAdsAdInfo, didEarnReward reward: QartveloAdsReward)
    optional func qartveloAdDidStartFallback(placementId: String, format: QartveloAdFormat, reason: String)
    optional func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat)
    optional func qartveloAdsDidReportSetupIssue(_ issue: QartveloAdsSetupIssue) // global observers only
}
```

Fallback `reason` values: `no_fill`, `timeout`, `error`, `creative_failed`, `disabled`.

`qartveloAdsDidReportSetupIssue(_:)` reports a configuration problem (see `QartveloAdsSetupIssue` below). Only observers added with `addObserver(_:)` receive it, once per process for each code and placement; the SDK also logs it as an error.

## Models

```swift
final class QartveloAdsOptions: NSObject {
    var admobFallback = true
    var requestTimeoutMs = 800
    var testMode = false
    var testModeInDebugBuilds = true // deprecated, ignored; non-App Store installs always use test mode
    var testForceNoFill = false
    var logLevel: QartveloAdsLogLevel = .error
    var baseURL = URL(string: "https://ads.qartvelo.com/")!
    var admobAdUnits: [String: String] = [:]
    var admobTestUnitsInDebugBuilds = true // accepted for parity with Android, ignored on iOS
}

final class QartveloAdsAdInfo: NSObject {
    let placementId: String
    let format: QartveloAdFormat     // .banner, .interstitial, .rewarded
    let source: QartveloAdSource     // .qartvelo, .admob
    let campaignId: String?          // Qartvelo Ads only
    let creativeId: String?
}

final class QartveloAdsReward: NSObject { let type: String; let amount: Int }

final class QartveloAdsSetupIssue: NSObject {
    static let packageMismatch = "package_mismatch"
    static let platformMismatch = "platform_mismatch"
    static let unknownPlacement = "unknown_placement"
    static let formatMismatch = "format_mismatch"
    let code: String          // one of the constants above
    let message: String       // what is wrong and how to fix it, in English
    let placementId: String?  // the placement concerned, nil for app-level issues
}

final class QartveloAdsError: NSObject, LocalizedError {
    let code: QartveloAdsErrorCode
    let message: String
}

enum QartveloAdsErrorCode: Int {
    case notInitialized, invalidPlacement, networkError, timeout, noFill, creativeFailed,
         adExpired, showFailed, alreadyShowing, internalError
}

struct QartveloAdsPrivacy {
    var consentGiven: Bool?
    var childDirected: Bool?
    var underAgeOfConsent: Bool?
}

enum QartveloAdsLogLevel: Int { case none, error, info, debug }
```

`QartveloAdsErrorCode.description` gives the same names as Android and React Native (`NO_FILL`, `TIMEOUT`, ...).

## QartveloAdsAdMob

```swift
@objc(QartveloAdMobFallbackAdapter)
final class QartveloAdMobFallbackAdapter: NSObject, QartveloFallbackAdapter { init() }
```

Register it with `QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter())`. In test mode it uses Google's iOS test ad units. To write an adapter for another network, implement `QartveloFallbackAdapter` (see `FallbackAdapter.swift`); the contract matches the [Android adapter](/guides/custom-fallback-adapter/).

Since 0.6.0, `QartveloFallbackAdapter` has an optional method for inline banners. The protocol extension's default returns `nil`, and the SDK then uses `createBanner` (anchored). The AdMob adapter implements it with Google's `inlineAdaptiveBanner(width:maxHeight:)`.

```swift
func createInlineBanner(
    placementId: String,
    adUnitId: String,
    width: CGFloat,
    maxHeight: CGFloat,
    rootViewController: UIViewController?,
    callback: QartveloFallbackBannerCallback
) -> QartveloFallbackBanner?
```
