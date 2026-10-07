---
title: Interstitial
description: Preload and show full-screen interstitial ads at natural breaks.
---

Interstitials are full-screen image or video ads shown at natural pauses: between levels, after finishing an article, when leaving a screen.

## Load early, show later

```kotlin
// When the level starts
QartveloAds.loadInterstitial("game_end", object : QartveloAdsListener {
    override fun onLoaded(info: QartveloAdsAdInfo) {
        // ready; info.source is QARTVELO or ADMOB
    }
    override fun onLoadFailed(placementId: String, error: QartveloAdsError) {
        // nothing ready; error.code tells you why
    }
})

// When the level ends
if (QartveloAds.isInterstitialReady("game_end")) {
    QartveloAds.showInterstitial(activity, "game_end", object : QartveloAdsListener {
        override fun onDismissed(info: QartveloAdsAdInfo) = continueGame()
        override fun onNoAdAvailable(placementId: String, format: AdFormat) = continueGame()
        override fun onLoadFailed(placementId: String, error: QartveloAdsError) = continueGame()
    })
} else {
    continueGame()
}
```

Always continue your flow from **every** terminal callback of the show: `onDismissed`, `onNoAdAvailable` and `onLoadFailed`. Exactly one of them ends each show.

## Load behaviour

1. A still-valid cached Qartvelo Ads ad completes the load immediately.
2. Otherwise the SDK requests Qartvelo Ads and, when a fallback is possible, **preloads AdMob in parallel**.
3. On a fill, the creative is downloaded and validated, then `onLoaded(source = QARTVELO)`.
4. On no-fill, timeout, network error, creative failure or kill switch: `onFallbackStarted(reason)`, then `onLoaded(source = ADMOB)` when AdMob is ready, or `onNoAdAvailable` followed by `onLoadFailed` if AdMob has nothing either.
5. If the Qartvelo Ads creative is still downloading 1.5 s after the request timeout, a ready AdMob ad completes the load; the Qartvelo Ads download continues and is used by the next show.

Concurrent loads of the same placement share one request.

## Show behaviour

- Show prefers a valid Qartvelo Ads ad, then a ready AdMob ad, else `onNoAdAvailable`.
- If a Qartvelo Ads creative fails to render before anything was displayed, a ready AdMob ad is shown instead.
- Only one full-screen ad can be on screen; a second show fails with `onLoadFailed(ALREADY_SHOWING)`.
- A Qartvelo Ads ad is shown at most once and never after its expiry (30 minutes after the request).
- Rotation and Activity re-creation do not restart the ad or repeat events.
- Tapping the ad records the click, then opens the advertiser's URL in the browser.
- Tapping the **Ad** badge in the top corner opens the Qartvelo Ads website (`https://ads.qartvelo.com/?ref=<your package name>`) instead. It is not an ad click, and the ad stays on screen.

## Listeners

Pass a listener to `showInterstitial`. Without one, show events go to the last load listener of that placement while your code still references it (load listeners are held weakly so a destroyed Activity is never retained), plus any global observers registered with `QartveloAds.addEventListener`.

## Good practice

- Preload as soon as you know an ad might be shown, not right before showing.
- Do not show interstitials on app launch, in the middle of gameplay, or right after another full-screen ad.
- Use a frequency cap on the placement (for example 1 per `hour`) rather than counting in code.
- After a show, load again for the next opportunity.
