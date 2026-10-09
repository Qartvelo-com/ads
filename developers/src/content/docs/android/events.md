---
title: Events and errors
description: QartveloAdsListener callbacks, global observers, event ordering and error codes.
---

All callbacks run on the **main thread**. Every method of `QartveloAdsListener` has an empty default body, so override only what you need.

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
    fun onSetupIssue(issue: QartveloAdsSetupIssue) {}
}
```

## Callbacks

| Callback | When |
|---|---|
| `onLoaded(info)` | A load finished and a source is ready. `info.source` is the one that will be shown |
| `onLoadFailed(placementId, error)` | A load ended without an ad, or a show-time error (`ALREADY_SHOWING`, `SHOW_FAILED`) |
| `onFallbackStarted(placementId, format, reason)` | Qartvelo Ads could not serve. `reason`: `no_fill`, `timeout`, `error`, `creative_failed`, `disabled` |
| `onNoAdAvailable(placementId, format)` | No source can serve. Before `onLoadFailed` on loads; alone on shows |
| `onShown(info)` | The creative is on screen |
| `onImpression(info)` | The impression was counted (one per ad) |
| `onClicked(info)` | First tap on the ad, recorded before the browser opens |
| `onReward(info, reward)` | Rewarded completion, at most once per show |
| `onDismissed(info)` | The full-screen ad closed |
| `onSetupIssue(issue)` | A setup problem to fix. Global listeners only, see [Setup issues](#setup-issues) |

## Ordering guarantees

- A **load** ends with exactly one of `onLoaded` or `onLoadFailed`. `onFallbackStarted` may precede either.
- A **show** produces `onShown` and `onImpression` when the creative is on screen, `onClicked` at most once, `onReward` at most once (rewarded only), and ends with exactly one of `onDismissed`, `onNoAdAvailable` or `onLoadFailed`.

## Global observers

Per-call listeners receive the events of that call. Global observers receive every event of every placement and format, including banners. Use them for analytics:

```kotlin
val analytics = object : QartveloAdsListener {
    override fun onImpression(info: QartveloAdsAdInfo) {
        log("ad_impression", info.placementId, info.source.name)
    }
    override fun onFallbackStarted(placementId: String, format: AdFormat, reason: String) {
        log("ad_fallback", placementId, reason)
    }
}
QartveloAds.addEventListener(analytics)
// later
QartveloAds.removeEventListener(analytics)
```

## Setup issues

A wrong app key or package, or a placement code the dashboard does not know, is a configuration problem rather than an ad failure. The SDK logs each one as an error and reports it to **global listeners** through `onSetupIssue` (per-call listeners never receive it). A given code and placement is reported once per process.

```kotlin
data class QartveloAdsSetupIssue(
    val code: String,         // see the table below
    val message: String,      // what is wrong and how to fix it, in English
    val placementId: String?, // the placement concerned, null for app-level issues
)
```

| `code` (constant) | Meaning |
|---|---|
| `package_mismatch` (`PACKAGE_MISMATCH`) | The app key is registered for another package name |
| `platform_mismatch` (`PLATFORM_MISMATCH`) | The app key belongs to the app of the other platform |
| `unknown_placement` (`UNKNOWN_PLACEMENT`) | The placement code does not exist for this app |
| `format_mismatch` (`FORMAT_MISMATCH`) | The placement exists with another format |

```kotlin
QartveloAds.addEventListener(object : QartveloAdsListener {
    override fun onSetupIssue(issue: QartveloAdsSetupIssue) {
        Log.w("Ads", "${issue.code}: ${issue.message}")
    }
})
```

## Ad info

```kotlin
data class QartveloAdsAdInfo(
    val placementId: String,
    val format: AdFormat,          // BANNER, INTERSTITIAL, REWARDED
    val source: AdSource,          // QARTVELO or ADMOB
    val campaignId: String? = null, // "cmp_12" for Qartvelo Ads ads, null for AdMob
    val creativeId: String? = null, // "cr_34" for Qartvelo Ads ads, null for AdMob
)
```

## Error codes

`QartveloAdsError(code: QartveloAdsErrorCode, message: String)`

| Code | Meaning |
|---|---|
| `NOT_INITIALIZED` | `initialize` was not called, the app key is empty, or the backend rejected the key/package |
| `INVALID_PLACEMENT` | Empty or unknown placement code, or a placement used with the wrong format |
| `NETWORK_ERROR` | The backend could not be reached and no fallback was available |
| `TIMEOUT` | The backend did not answer in time and no fallback was available |
| `NO_FILL` | Neither Qartvelo Ads nor the fallback had an ad |
| `CREATIVE_FAILED` | The creative could not be downloaded or decoded |
| `AD_EXPIRED` | The ad passed its expiry before it was shown |
| `SHOW_FAILED` | The ad could not be displayed (for example the Activity was finishing) |
| `ALREADY_SHOWING` | Another full-screen ad is on screen |
| `INTERNAL_ERROR` | Unexpected failure inside the SDK. The SDK never throws into your code |

The `message` is for logs only; branch on `code`.
