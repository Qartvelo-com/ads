---
title: Initialization
description: Initialize the iOS SDK once, configure options, and pass privacy signals.
---

Initialize once, as early as possible, normally in `application(_:didFinishLaunchingWithOptions:)`. The call returns immediately; networking happens in the background.

```swift title="AppDelegate.swift"
import QartveloAds
import QartveloAdsAdMob // only with the AdMob fallback

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Optional, any time: privacy signals from your consent flow (see Privacy guide)
        QartveloAds.setPrivacy(QartveloAdsPrivacy(consentGiven: consentOrNil()))
        QartveloAds.registerFallbackAdapter(QartveloAdMobFallbackAdapter())

        let options = QartveloAdsOptions()
        options.admobAdUnits = ["game_end": "ca-app-pub-XXXXXXXXXXXXXXXX/NNNNNNNNNN"]
        #if DEBUG
        options.logLevel = .debug
        #endif

        QartveloAds.initialize(appKey: "app_xxxxxxxxxxxxxxxxxxxxxxxx", options: options) { success, error in
            // Main thread. success == false still leaves the SDK usable.
        }
        return true
    }
}
```

In a SwiftUI app, call the same code from `init()` of your `App` struct or from a `UIApplicationDelegateAdaptor`.

## Options

`QartveloAdsOptions` is a class; set its properties before calling `initialize`.

| Option | Type | Default | Meaning |
|---|---|---|---|
| `admobFallback` | `Bool` | `true` | Allow the AdMob adapter to serve your units when Qartvelo Ads cannot |
| `requestTimeoutMs` | `Int` | `800` | Qartvelo Ads time budget before falling back. A per-placement value from the dashboard wins. Clamped to 100..10000 |
| `testMode` | `Bool` | `false` | Non-billable test ads labelled "Test ad"; AdMob uses Google's test units. See [Test mode](/get-started/test-mode/) |
| `testModeInDebugBuilds` | `Bool` | `true` | Deprecated; retained for compatibility and ignored. All non-App Store installations always use test mode |
| `admobTestUnitsInDebugBuilds` | `Bool` | `true` | Accepted for parity with Android and ignored on iOS, where every installation outside the App Store already uses Google's test units |
| `testForceNoFill` | `Bool` | `false` | Force Qartvelo Ads `no_fill` to exercise the fallback |
| `logLevel` | `QartveloAdsLogLevel` | `.error` | `.none`, `.error`, `.info`, `.debug`. Unified logging subsystem `com.qartvelo.ads` |
| `baseURL` | `URL` | `https://ads.qartvelo.com/` | API origin. Change only for a self-hosted or local backend |
| `admobAdUnits` | `[String: String]` | empty | Placement code to your AdMob ad unit id. Wins over the dashboard value |

## Test mode on iOS

- The **iOS Simulator is always in test mode**: its traffic is never billed.
- **Every installation outside the App Store is in test mode**, including Xcode, development, ad hoc, enterprise and TestFlight builds, even when both test options are false. Live traffic requires a physical App Store installation with a production receipt present and no embedded provisioning profile. A missing or sandbox receipt defaults to test mode. The SDK never requests a receipt refresh or a purchase prompt.
- For an approved app with a matching campaign, the server can show the real campaign creative marked **Test ad**. Its impressions and clicks do not contribute to live reports, advertiser charges or publisher earnings.
- In test mode, an empty app key skips session initialization. If the backend rejects the key or bundle ID, an ad request fails, or there is no test fill, the SDK downloads the matching public Qartvelo test creative from `/test-ads/` without an app session and initialization still succeeds. This lets you test before registering the iOS app. These test creatives do not send impression, click, or reward events to the backend.

## Behaviour

- **Idempotent.** Only the first call counts. Later calls are ignored, but their completion still receives the first result. Restart the app to change options.
- **Never blocks loads on a slow backend.** With a cached remote config, loads start immediately and the Qartvelo Ads request obtains its session within its own timeout. Only the very first start (no cache yet) holds loads for up to `requestTimeoutMs`.
- **Failure is not fatal.** Outside test mode, if initialization fails (offline, backend down, key rejected), the completion gets `success == false`, the SDK runs on its cached config and can still fall back to AdMob. In test mode, it succeeds when registration is unavailable and loads public server test creatives without an app session.
- `QartveloAds.isInitialized` becomes `true` when the first attempt has finished, successfully or not.
- Loads before `initialize` was called fail with `notInitialized`.
- Call `initialize` from the main thread (the app delegate is). Calls from other threads are moved to the main thread.

Outside test mode, typical initialization errors are `networkError` and `timeout` (backend unreachable), or `notInitialized` (empty or rejected app key, wrong bundle ID or platform, app not approved). In test mode, backend registration failures do not block public server test creatives.

## Runtime settings

```swift
QartveloAds.setLogLevel(.debug) // overrides options.logLevel
QartveloAds.setPrivacy(QartveloAdsPrivacy(childDirected: true))
```

Both can be called before or after `initialize`. See the [Privacy guide](/guides/privacy/).

## App key safety

The app key is public and identifies your app; the backend accepts it only together with the bundle ID registered in the dashboard. Do not put the SDK secret in your app.
