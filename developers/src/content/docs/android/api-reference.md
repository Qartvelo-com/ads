---
title: API reference
description: Complete public API of the Qartvelo Ads Android SDK 0.6.0 (package com.qartvelo.sdk).
---

Package `com.qartvelo.sdk`, version `0.6.0` (`QartveloAds.SDK_VERSION`). Every method is safe to call from any thread, never throws and delivers callbacks on the main thread. `@JvmStatic` and `@JvmOverloads` make the API callable from Java as `QartveloAds.initialize(...)`.

## QartveloAds

```kotlin
object QartveloAds {
    const val SDK_VERSION: String = "0.6.0"

    fun initialize(
        context: Context,
        appKey: String,
        options: QartveloAdsOptions = QartveloAdsOptions(),
        listener: QartveloAdsInitListener? = null,
    )
    fun isInitialized(): Boolean

    fun loadInterstitial(placementId: String, listener: QartveloAdsListener? = null)
    fun showInterstitial(activity: Activity, placementId: String, listener: QartveloAdsListener? = null)
    fun isInterstitialReady(placementId: String): Boolean

    fun loadRewarded(placementId: String, listener: QartveloAdsListener? = null)
    fun showRewarded(activity: Activity, placementId: String, listener: QartveloAdsListener? = null)
    fun isRewardedReady(placementId: String): Boolean

    fun addEventListener(listener: QartveloAdsListener)
    fun removeEventListener(listener: QartveloAdsListener)

    fun setLogLevel(level: QartveloAdsLogLevel)
    fun setPrivacy(privacy: QartveloAdsPrivacy)
    fun registerFallbackAdapter(adapter: FallbackAdapter)
}
```

| Method | Description |
|---|---|
| `initialize` | Starts the SDK once per process. See [Initialization](/android/initialization/) |
| `isInitialized` | `true` once the first initialization attempt finished (also offline) |
| `loadInterstitial` / `loadRewarded` | Loads an ad for a placement code; ends with `onLoaded` or `onLoadFailed` |
| `showInterstitial` / `showRewarded` | Shows the best ready ad; ends with `onDismissed`, `onNoAdAvailable` or `onLoadFailed` |
| `isInterstitialReady` / `isRewardedReady` | Whether a show would display an ad now |
| `addEventListener` / `removeEventListener` | Global observers for every placement, banners included, and for setup issues (`onSetupIssue`) |
| `setLogLevel` | Overrides `options.logLevel` at runtime |
| `setPrivacy` | Privacy signals forwarded to the fallback adapter. See [Privacy](/guides/privacy/) |
| `registerFallbackAdapter` | Use a custom fallback network. See [Custom fallback adapter](/guides/custom-fallback-adapter/) |

## QartveloAdsOptions

```kotlin
data class QartveloAdsOptions(
    val admobFallback: Boolean = true,
    val requestTimeoutMs: Long = 800,
    val testMode: Boolean = false,
    val testForceNoFill: Boolean = false,
    val logLevel: QartveloAdsLogLevel = QartveloAdsLogLevel.ERROR,
    val baseUrl: String = DEFAULT_BASE_URL, // "https://ads.qartvelo.com/"
    val admobAdUnits: Map<String, String> = emptyMap(),
    val testModeInDebugBuilds: Boolean = true, // test mode in debuggable builds
    val admobTestUnitsInDebugBuilds: Boolean = true, // Google's test units for the AdMob fallback in debuggable builds
)
```

## QartveloAdsBannerView

```kotlin
class QartveloAdsBannerView(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : FrameLayout {
    var placementId: String?            // XML: app:qartvelo_placementId
    var listener: QartveloAdsListener?
    var usesAdaptiveSize: Boolean       // default true: anchored adaptive slot; false: the creative's size
    var sizing: BannerSizing            // default ANCHORED; INLINE for banners in scrolling content (wins over usesAdaptiveSize)
    var inlineMaxHeightDp: Int          // inline only: the most the banner may be tall, default 250, at least 32
    fun load()                          // idempotent
    fun destroy()                       // detaches; the loaded banner stays cached
}

enum class BannerSizing { ANCHORED, INLINE }
```

Set `sizing` and `inlineMaxHeightDp` before `load()`. `ANCHORED`: Google's anchored adaptive slot, full width and 50 to 90 dp tall. `INLINE`: the ad takes the biggest size that fits the width and `inlineMaxHeightDp` keeping its proportions, and the view is as tall as that ad. See [Inline banners](/android/banner/#inline-banners).

## Listeners

```kotlin
interface QartveloAdsListener {
    fun onLoaded(info: QartveloAdsAdInfo) {}
    fun onLoadFailed(placementId: String, error: QartveloAdsError) {}
    fun onShown(info: QartveloAdsAdInfo) {}
    fun onImpression(info: QartveloAdsAdInfo) {}
    fun onClicked(info: QartveloAdsAdInfo) {}
    fun onDismissed(info: QartveloAdsAdInfo) {}
    fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) {}
    fun onFallbackStarted(placementId: String, format: AdFormat, reason: String) {}
    fun onNoAdAvailable(placementId: String, format: AdFormat) {}
    fun onSetupIssue(issue: QartveloAdsSetupIssue) {} // global listeners only
}

fun interface QartveloAdsInitListener {
    fun onInitialized(success: Boolean, error: QartveloAdsError?)
}
```

## Models

```kotlin
enum class QartveloAdsLogLevel { NONE, ERROR, INFO, DEBUG }
enum class AdFormat { BANNER, INTERSTITIAL, REWARDED }
enum class AdSource { QARTVELO, ADMOB }

data class QartveloAdsAdInfo(
    val placementId: String,
    val format: AdFormat,
    val source: AdSource,
    val campaignId: String? = null,
    val creativeId: String? = null,
)

data class QartveloAdsReward(val type: String = "reward", val amount: Int = 1)

data class QartveloAdsSetupIssue(
    val code: String,          // PACKAGE_MISMATCH, PLATFORM_MISMATCH, UNKNOWN_PLACEMENT or FORMAT_MISMATCH
    val message: String,       // what is wrong and how to fix it, in English
    val placementId: String?,  // the placement concerned, null for app-level issues
) {
    companion object {
        const val PACKAGE_MISMATCH = "package_mismatch"
        const val PLATFORM_MISMATCH = "platform_mismatch"
        const val UNKNOWN_PLACEMENT = "unknown_placement"
        const val FORMAT_MISMATCH = "format_mismatch"
    }
}

enum class QartveloAdsErrorCode {
    NOT_INITIALIZED, INVALID_PLACEMENT, NETWORK_ERROR, TIMEOUT, NO_FILL,
    CREATIVE_FAILED, AD_EXPIRED, SHOW_FAILED, ALREADY_SHOWING, INTERNAL_ERROR,
}
data class QartveloAdsError(val code: QartveloAdsErrorCode, val message: String)

data class QartveloAdsPrivacy(
    val consentGiven: Boolean? = null,      // null = unknown
    val childDirected: Boolean? = null,
    val underAgeOfConsent: Boolean? = null,
)
```

## Fallback seam

Package `com.qartvelo.sdk.fallback`: `FallbackAdapter`, `FallbackSettings`, `FallbackLoadCallback`, `FallbackShowCallback`, `FallbackBannerCallback`, `FallbackBanner`, and the optional `AdaptiveBannerSizer` and `InlineBannerFallback` (since 0.6.0):

```kotlin
interface InlineBannerFallback {
    fun createInlineBanner(
        context: Context,
        placementId: String,
        adUnitId: String,
        widthDp: Int,
        maxHeightDp: Int,
        callback: FallbackBannerCallback,
    ): FallbackBanner
}
```

Adapters without `InlineBannerFallback` get `createBanner` for inline banners too. The AdMob adapter implements both optional interfaces. Documented in [Custom fallback adapter](/guides/custom-fallback-adapter/).

## Java

```java
QartveloAds.initialize(this, "app_xxxxxxxxxxxxxxxxxxxxxxxx",
        new QartveloAdsOptions(true, 800L, BuildConfig.DEBUG, false,
                QartveloAdsLogLevel.ERROR, "https://ads.qartvelo.com/", Collections.emptyMap()));

QartveloAds.loadInterstitial("game_end");
QartveloAds.showInterstitial(activity, "game_end");
```

`QartveloAdsOptions` has `@JvmOverloads`, so leading arguments can be passed and the rest default (for example `new QartveloAdsOptions(true, 800L, BuildConfig.DEBUG)`).
