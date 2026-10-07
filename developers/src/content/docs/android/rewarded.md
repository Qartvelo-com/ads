---
title: Rewarded
description: Show rewarded videos and grant rewards exactly once, only after confirmed completion.
---

Rewarded placements play a video the user opts into in exchange for an in-app reward (coins, an extra life, a hint).

```kotlin
// Preload, for example when the shop screen opens
QartveloAds.loadRewarded("reward_coins")

// The user taps "Watch a video for 50 coins"
QartveloAds.showRewarded(activity, "reward_coins", object : QartveloAdsListener {
    override fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) {
        grantCoins(50) // exactly once, only after completion
    }
    override fun onDismissed(info: QartveloAdsAdInfo) = resumeGame()
    override fun onNoAdAvailable(placementId: String, format: AdFormat) = showNoVideoMessage()
    override fun onLoadFailed(placementId: String, error: QartveloAdsError) = showNoVideoMessage()
})
```

Use `QartveloAds.isRewardedReady("reward_coins")` to enable or hide the "watch a video" button.

## Reward guarantees

- `onReward` fires **at most once per show** and **only after confirmed completion**, whether Qartvelo Ads or the AdMob fallback served the ad.
- Qartvelo Ads rewards are `QartveloAdsReward(type = "reward", amount = 1)`. AdMob rewards carry the type and amount configured on your AdMob unit (`onUserEarnedReward` is mapped to `onReward`). Most apps ignore the amount and grant their own fixed reward.
- Closing a Qartvelo Ads video early asks the user for confirmation and forfeits the reward.
- The **Ad** badge opens the Qartvelo Ads website with `?ref=<your package name>`; it is not an ad click, and the video pauses while the browser is open.
- Rotation and Activity re-creation neither restart the video nor repeat events.
- The SDK reports the completion to the backend (`POST /events/reward`) for auditing; your app should grant the reward from the callback, not wait for the server.

## Video playback

Qartvelo Ads rewarded videos are MP4 (H.264), 5 to 60 seconds long, played full-screen with Media3 ExoPlayer from a file downloaded before `onLoaded`. Playback never starts streaming over a slow network.
