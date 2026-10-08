---
title: Rewarded
description: Rewarded video ads on iOS that grant the reward only after the video was watched.
---

Rewarded ads are full-screen videos the user chooses to watch in exchange for an in-app reward.

```swift
final class ShopViewController: UIViewController, QartveloAdsDelegate {
    override func viewDidLoad() {
        super.viewDidLoad()
        QartveloAds.loadRewarded("reward_coins", delegate: self)
    }

    @objc func watchVideoTapped() {
        QartveloAds.showRewarded("reward_coins", from: self, delegate: self)
    }

    func qartveloAd(_ info: QartveloAdsAdInfo, didEarnReward reward: QartveloAdsReward) {
        grantCoins(10) // the video was watched to the end
    }

    func qartveloAdDidDismiss(_ info: QartveloAdsAdInfo) {
        QartveloAds.loadRewarded("reward_coins", delegate: self)
    }

    func qartveloAdNoAdAvailable(placementId: String, format: QartveloAdFormat) {
        showMessage("No video available right now")
    }
}
```

## Reward rules

- `qartveloAd(_:didEarnReward:)` is called **at most once** per show and only after the video played to the end (for AdMob, when Google reports the reward). It always comes before `qartveloAdDidDismiss`.
- Grant the reward in that callback, not in `qartveloAdDidDismiss`: a user who closes early gets no reward.
- The close button appears after 2 seconds. Closing before the end asks the user to confirm ("Close video?") and pauses the video meanwhile.
- The Qartvelo Ads reward is `type == "reward"`, `amount == 1`; decide the actual in-app amount yourself. An AdMob reward carries the type and amount configured in AdMob.
- Rewarded placements serve video creatives only.

Loading, readiness (`isRewardedReady`) and errors work exactly like [interstitials](/ios/interstitial/).
