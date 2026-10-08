---
title: Interstitial
description: Preload and show full-screen interstitial ads at natural breaks on iOS.
---

Interstitials are full-screen image or video ads shown at natural pauses: between levels, after finishing an article, when leaving a screen.

## Load early, show later

```swift
final class GameViewController: UIViewController, QartveloAdsDelegate {
    override func viewDidLoad() {
        super.viewDidLoad()
        // When the level starts
        QartveloAds.loadInterstitial("game_end", delegate: self)
    }

    func levelFinished() {
        if QartveloAds.isInterstitialReady("game_end") {
            QartveloAds.showInterstitial("game_end", from: self, delegate: self)
        } else {
            continueGame()
        }
    }

    func qartveloAdDidLoad(_ info: QartveloAdsAdInfo) {}
    func qartveloAdDidFailToLoad(placementId: String, error: QartveloAdsError) {}
    func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat) { continueGame() }
    func qartveloAdDidDismiss(_ info: QartveloAdsAdInfo) {
        continueGame()
        QartveloAds.loadInterstitial("game_end", delegate: self) // preload the next one
    }
}
```

## Load

- A load first asks Qartvelo Ads (within `requestTimeoutMs`) while the AdMob fallback preloads in parallel. The creative is downloaded and checked before `qartveloAdDidLoad`, so a loaded ad shows instantly.
- Exactly one of `qartveloAdDidLoad` or `qartveloAdDidFailToLoad` ends every load. Loads issued while one is running join it.
- If Qartvelo Ads has no ad, `qartveloAdDidStartFallback(placementId:format:reason:)` comes first, then `qartveloAdDidLoad` with `source == .admob`, or `qartveloAdNoAdAvailable` and `qartveloAdDidFailToLoad`.
- A Qartvelo Ads ad is shown at most once and never after its expiry (30 minutes after the request); an expired ad is dropped and the next load fetches a fresh one.

## Show

- `showInterstitial(_:from:delegate:)` presents full screen from the given view controller (or the view controller it is presenting). Without a delegate, the delegate of the last load is used.
- Events: `qartveloAdDidShow`, `qartveloAdDidRecordImpression`, `qartveloAdDidClick` (at most once), then `qartveloAdDidDismiss`.
- Nothing loaded: `qartveloAdNoAdAvailable`. Another full-screen ad already on screen: `qartveloAdDidFailToLoad` with `alreadyShowing`.
- The close button appears after 2 seconds for images and 5 seconds for videos. Videos play with sound, pause when the app goes to the background and resume when it returns.
- Each loaded ad can be shown once. Load again after `qartveloAdDidDismiss`.
