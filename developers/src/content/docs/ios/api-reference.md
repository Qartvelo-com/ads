---
title: API reference
description: Complete public API of the Qartvelo Ads iOS SDK 0.4.0 (modules QartveloAds and QartveloAdsAdMob).
---

Module `QartveloAds`, version `0.4.0` (`QartveloAds.sdkVersion`). Every method is safe to call from any thread, never throws, and delivers callbacks on the main thread. All types are available to Objective-C except `QartveloAdsPrivacy` and the fallback adapter protocols.

## QartveloAds

```swift
final class QartveloAds: NSObject {
    static let sdkVersion: String // "0.4.0"

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
    func load()    // idempotent
    func destroy()
}
```

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
}
```

Fallback `reason` values: `no_fill`, `timeout`, `error`, `creative_failed`, `disabled`.

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
}

final class QartveloAdsAdInfo: NSObject {
    let placementId: String
    let format: QartveloAdFormat     // .banner, .interstitial, .rewarded
    let source: QartveloAdSource     // .qartvelo, .admob
    let campaignId: String?          // Qartvelo Ads only
    let creativeId: String?
}

final class QartveloAdsReward: NSObject { let type: String; let amount: Int }

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
