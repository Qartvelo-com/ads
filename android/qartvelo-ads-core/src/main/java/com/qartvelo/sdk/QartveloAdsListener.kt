package com.qartvelo.sdk

/**
 * Ad lifecycle callbacks. Every method has an empty default body and is invoked on the main thread.
 *
 * Load outcome: exactly one of [onLoaded] or [onLoadFailed] ends each load call. When the failure is
 * "no ad from any source", [onNoAdAvailable] is emitted right before [onLoadFailed]. When QartveloAds cannot
 * serve and a fallback is possible, [onFallbackStarted] precedes the final outcome.
 *
 * Show outcome: [onShown] then [onImpression] when the creative is on screen, [onClicked] at most once,
 * [onReward] at most once (rewarded only, after confirmed completion), and finally [onDismissed].
 * If nothing can be shown, [onNoAdAvailable] is emitted instead; show-time errors such as
 * [QartveloAdsErrorCode.ALREADY_SHOWING] are reported through [onLoadFailed].
 */
public interface QartveloAdsListener {
    public fun onLoaded(info: QartveloAdsAdInfo) {}
    public fun onLoadFailed(placementId: String, error: QartveloAdsError) {}
    public fun onShown(info: QartveloAdsAdInfo) {}
    public fun onImpression(info: QartveloAdsAdInfo) {}
    public fun onClicked(info: QartveloAdsAdInfo) {}
    public fun onDismissed(info: QartveloAdsAdInfo) {}
    public fun onReward(info: QartveloAdsAdInfo, reward: QartveloAdsReward) {}
    public fun onFallbackStarted(placementId: String, format: AdFormat, reason: String) {}
    public fun onNoAdAvailable(placementId: String, format: AdFormat) {}

    /** A setup problem to fix (wrong key or package, missing placement). Delivered to global listeners. */
    public fun onSetupIssue(issue: QartveloAdsSetupIssue) {}
}

/**
 * Initialization result, on the main thread. `success == false` still leaves the SDK usable: it runs on
 * the last cached remote configuration (if any), retries the session lazily and can fall back to AdMob.
 */
public fun interface QartveloAdsInitListener {
    public fun onInitialized(success: Boolean, error: QartveloAdsError?)
}
