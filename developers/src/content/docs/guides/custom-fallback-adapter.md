---
title: Custom fallback adapter
description: Plug another ad network into the fallback seam with the FallbackAdapter interface.
---

The AdMob adapter is one implementation of a small interface in `qartvelo-ads-core`. You can implement it yourself to fall back to another network, or to your own house ads.

```kotlin
QartveloAds.registerFallbackAdapter(MyNetworkAdapter())
```

Register before or after `initialize`; a registered adapter replaces the auto-discovered AdMob adapter. The fallback rules are unchanged: the placement's fallback provider must not be `none`, `admobFallback` must be `true`, and an ad unit id must be known (`admobAdUnits` or the dashboard), or test mode must be on. The id string is passed to your adapter as-is, so you can map placement codes to your own network's ids through `admobAdUnits`.

## Interface

Package `com.qartvelo.sdk.fallback`:

```kotlin
interface FallbackAdapter {
    val networkName: String                                        // for logs, e.g. "mynetwork"
    fun initialize(context: Context, settings: FallbackSettings)    // once; cheap; never throw
    fun updateSettings(settings: FallbackSettings)                  // test mode or privacy changed

    fun loadInterstitial(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback)
    fun isInterstitialReady(placementId: String): Boolean
    fun showInterstitial(activity: Activity, placementId: String, callback: FallbackShowCallback)

    fun loadRewarded(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback)
    fun isRewardedReady(placementId: String): Boolean
    fun showRewarded(activity: Activity, placementId: String, callback: FallbackShowCallback)

    fun createBanner(context: Context, placementId: String, adUnitId: String, widthDp: Int,
                     callback: FallbackBannerCallback): FallbackBanner
}

data class FallbackSettings(val testMode: Boolean, val privacy: QartveloAdsPrivacy)

interface FallbackLoadCallback { fun onLoaded(); fun onFailed(message: String) }

interface FallbackShowCallback {
    fun onShown() {}
    fun onImpression() {}
    fun onClicked() {}
    fun onReward(type: String, amount: Int) {}   // only after the network confirmed the reward
    fun onDismissed() {}
    fun onShowFailed(message: String) {}
}

interface FallbackBannerCallback {
    fun onLoaded(); fun onFailed(message: String)
    fun onImpression() {}; fun onClicked() {}
}

interface FallbackBanner {
    val view: View
    fun pause(); fun resume(); fun destroy()
}
```

## Contract

- **Threading**: core calls every method on the main thread. Callbacks may be invoked from any thread; core marshals them to the main thread and de-duplicates terminal events.
- **Keys**: ads are keyed by Qartvelo Ads placement id, so two placements may share one network unit.
- **Test mode**: when `settings.testMode` is true, substitute the network's public test units. The flag is true in Qartvelo test mode and also in debuggable Android builds (unless the app sets `admobTestUnitsInDebugBuilds = false`), so it can be true while Qartvelo test mode is off. An empty `adUnitId` is only ever passed in Qartvelo test mode.
- **Rewards**: call `onReward` only after the network confirmed the reward; core guarantees the app sees it at most once.
- **Banners**: `createBanner` receives a `MutableContextWrapper` owned by core, which swaps its base context between Activities so the banner never leaks one. Start loading immediately and report through the callback; core calls `pause`, `resume` and `destroy`.
- **Banner height** (optional): also implement `AdaptiveBannerSizer` and return the height in dp of your network's anchored adaptive banner for `widthDp`. Qartvelo Ads banners then reserve the same slot, so switching to your banner never moves the layout. Heights outside 50 to 90 dp are ignored; without the interface, core uses its own formula.
- **Never throw**: the SDK guards calls, but a throwing adapter turns every fallback into a failure.
- **R8**: core's consumer rules keep the `com.qartvelo.sdk.fallback` interfaces; keep your adapter class if you minify and load it by name.

The AdMob implementation, `com.qartvelo.admob.AdMobFallbackAdapter` in the [SDK repository](https://github.com/Qartvelo-com/ads/tree/main/android/qartvelo-ads-admob), is a complete reference.
