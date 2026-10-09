package com.qartvelo.sdk.fallback

import android.app.Activity
import android.content.Context
import android.view.View
import com.qartvelo.sdk.QartveloAdsPrivacy

/**
 * Seam between `qartvelo-ads-core` and an optional fallback network (the `qartvelo-ads-admob` module).
 *
 * Threading: core calls every method on the main thread. Implementations may invoke callbacks on any
 * thread; core marshals them back to the main thread and de-duplicates terminal events.
 *
 * Ads are keyed by QartveloAds placement id, so two placements may share one network ad unit id.
 * When [FallbackSettings.testMode] is true the adapter must substitute the network's public test ad
 * units for the supplied ids. That flag means "use the network's public test units": it is true in
 * Qartvelo test mode and in a debuggable build unless the app opted out with
 * `QartveloAdsOptions.admobTestUnitsInDebugBuilds`. An empty [adUnitId] is only ever passed in
 * Qartvelo test mode, where the flag is always true.
 */
public interface FallbackAdapter {
    /** Short network name used in logs, for example `admob`. */
    public val networkName: String

    /** Called once after QartveloAds initialization starts. Must be cheap and never throw. */
    public fun initialize(context: Context, settings: FallbackSettings)

    /** Called when test mode or privacy signals change. */
    public fun updateSettings(settings: FallbackSettings)

    public fun loadInterstitial(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback)

    public fun isInterstitialReady(placementId: String): Boolean

    public fun showInterstitial(activity: Activity, placementId: String, callback: FallbackShowCallback)

    public fun loadRewarded(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback)

    public fun isRewardedReady(placementId: String): Boolean

    public fun showRewarded(activity: Activity, placementId: String, callback: FallbackShowCallback)

    /**
     * Creates and starts loading a banner sized for [widthDp]. [context] is a `MutableContextWrapper`
     * owned by core, which swaps its base context between Activities so the banner never leaks one.
     */
    public fun createBanner(
        context: Context,
        placementId: String,
        adUnitId: String,
        widthDp: Int,
        callback: FallbackBannerCallback,
    ): FallbackBanner
}

public data class FallbackSettings(
    /**
     * Use the network's public test ad units instead of the supplied ids. True in Qartvelo test mode,
     * and in a debuggable build unless `QartveloAdsOptions.admobTestUnitsInDebugBuilds` is false, so it
     * can be true while Qartvelo test mode is off.
     */
    val testMode: Boolean,
    val privacy: QartveloAdsPrivacy,
)

public interface FallbackLoadCallback {
    public fun onLoaded()
    public fun onFailed(message: String)
}

public interface FallbackShowCallback {
    public fun onShown() {}
    public fun onImpression() {}
    public fun onClicked() {}
    /** Must only be called after the network confirmed the reward was earned. */
    public fun onReward(type: String, amount: Int) {}
    public fun onDismissed() {}
    public fun onShowFailed(message: String) {}
}

public interface FallbackBannerCallback {
    public fun onLoaded()
    public fun onFailed(message: String)
    public fun onImpression() {}
    public fun onClicked() {}
}

/** A fallback banner instance owned by core's per-placement banner controller. */
public interface FallbackBanner {
    public val view: View
    public fun pause()
    public fun resume()
    public fun destroy()
}
