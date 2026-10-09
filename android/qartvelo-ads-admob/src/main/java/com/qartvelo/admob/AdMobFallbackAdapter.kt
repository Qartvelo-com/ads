package com.qartvelo.admob

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.View
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.qartvelo.sdk.QartveloAdsPrivacy
import com.qartvelo.sdk.fallback.AdaptiveBannerSizer
import com.qartvelo.sdk.fallback.FallbackAdapter
import com.qartvelo.sdk.fallback.FallbackBanner
import com.qartvelo.sdk.fallback.FallbackBannerCallback
import com.qartvelo.sdk.fallback.FallbackLoadCallback
import com.qartvelo.sdk.fallback.FallbackSettings
import com.qartvelo.sdk.fallback.FallbackShowCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google Mobile Ads implementation of the QartveloAds fallback seam. qartvelo-ads-core instantiates it by reflection
 * (public no-arg constructor) when this module is on the classpath.
 *
 * - Uses the publisher's own AdMob App ID (host manifest) and ad unit ids; QartveloAds never proxies revenue.
 * - Test mode swaps every unit for Google's public test units.
 * - Consent: the publisher's own Google Mobile Ads/UMP configuration is left untouched. Only explicit
 *   QartveloAds privacy signals are forwarded (child / teen age-restricted treatment, and non-personalized
 *   ads when the app reports that consent was refused). Nothing here grants consent.
 */
public class AdMobFallbackAdapter : FallbackAdapter, AdaptiveBannerSizer {
    override val networkName: String = "admob"

    @Volatile
    private var settings = FallbackSettings(testMode = false, privacy = QartveloAdsPrivacy())

    private val interstitials = ConcurrentHashMap<String, Loaded<InterstitialAd>>()
    private val rewardeds = ConcurrentHashMap<String, Loaded<RewardedAd>>()

    private class Loaded<T>(val ad: T, val loadedAt: Long = SystemClock.elapsedRealtime()) {
        fun isFresh(): Boolean = SystemClock.elapsedRealtime() - loadedAt < AD_MAX_AGE_MS
    }

    override fun initialize(context: Context, settings: FallbackSettings) {
        this.settings = settings
        applyRequestConfiguration(settings.privacy)
        MobileAdsStarter.start(context.applicationContext)
    }

    override fun updateSettings(settings: FallbackSettings) {
        this.settings = settings
        applyRequestConfiguration(settings.privacy)
    }

    // ---- interstitial ---------------------------------------------------------------------------

    override fun loadInterstitial(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback) {
        val unit = AdMobUnits.resolve(AdMobUnits.Format.INTERSTITIAL, adUnitId, settings.testMode)
            ?: return callback.onFailed("no AdMob interstitial unit configured")
        InterstitialAd.load(context, unit, buildRequest(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitials[placementId] = Loaded(ad)
                callback.onLoaded()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                callback.onFailed("AdMob ${error.code}: ${error.message}")
            }
        })
    }

    override fun isInterstitialReady(placementId: String): Boolean = interstitials.fresh(placementId) != null

    override fun showInterstitial(activity: Activity, placementId: String, callback: FallbackShowCallback) {
        val ad = interstitials.remove(placementId)?.takeIf { it.isFresh() }?.ad
            ?: return callback.onShowFailed("no AdMob interstitial loaded")
        ad.fullScreenContentCallback = ShowRelay(callback)
        ad.show(activity)
    }

    // ---- rewarded -------------------------------------------------------------------------------

    override fun loadRewarded(context: Context, placementId: String, adUnitId: String, callback: FallbackLoadCallback) {
        val unit = AdMobUnits.resolve(AdMobUnits.Format.REWARDED, adUnitId, settings.testMode)
            ?: return callback.onFailed("no AdMob rewarded unit configured")
        RewardedAd.load(context, unit, buildRequest(), object : RewardedAdLoadCallback() {
            override fun onAdLoaded(ad: RewardedAd) {
                rewardeds[placementId] = Loaded(ad)
                callback.onLoaded()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                callback.onFailed("AdMob ${error.code}: ${error.message}")
            }
        })
    }

    override fun isRewardedReady(placementId: String): Boolean = rewardeds.fresh(placementId) != null

    override fun showRewarded(activity: Activity, placementId: String, callback: FallbackShowCallback) {
        val ad = rewardeds.remove(placementId)?.takeIf { it.isFresh() }?.ad
            ?: return callback.onShowFailed("no AdMob rewarded ad loaded")
        ad.fullScreenContentCallback = ShowRelay(callback)
        val rewarded = AtomicBoolean()
        // Google only calls this after the user earned the reward; core still guards "exactly once".
        ad.show(activity) { item ->
            if (rewarded.compareAndSet(false, true)) callback.onReward(item.type, item.amount)
        }
    }

    // ---- banner ---------------------------------------------------------------------------------

    override fun createBanner(
        context: Context,
        placementId: String,
        adUnitId: String,
        widthDp: Int,
        callback: FallbackBannerCallback,
    ): FallbackBanner {
        val unit = AdMobUnits.resolve(AdMobUnits.Format.BANNER, adUnitId, settings.testMode)
        val adView = AdView(context)
        if (unit == null) {
            callback.onFailed("no AdMob banner unit configured")
            return AdViewBanner(adView)
        }
        adView.adUnitId = unit
        adView.setAdSize(AdMobBannerSize.forWidth(context, widthDp))
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() = callback.onLoaded()
            override fun onAdFailedToLoad(error: LoadAdError) = callback.onFailed("AdMob ${error.code}: ${error.message}")
            override fun onAdImpression() = callback.onImpression()
            override fun onAdClicked() = callback.onClicked()
        }
        adView.loadAd(buildRequest())
        return AdViewBanner(adView)
    }

    /** QartveloAds banners reserve the same slot as the AdMob banner this adapter would show. */
    override fun adaptiveBannerHeightDp(context: Context, widthDp: Int): Int =
        AdMobBannerSize.forWidth(context, widthDp).height

    private class AdViewBanner(private val adView: AdView) : FallbackBanner {
        override val view: View get() = adView
        override fun pause() = adView.pause()
        override fun resume() = adView.resume()
        override fun destroy() = adView.destroy()
    }

    private class ShowRelay(private val callback: FallbackShowCallback) : FullScreenContentCallback() {
        override fun onAdShowedFullScreenContent() = callback.onShown()
        override fun onAdImpression() = callback.onImpression()
        override fun onAdClicked() = callback.onClicked()
        override fun onAdDismissedFullScreenContent() = callback.onDismissed()
        override fun onAdFailedToShowFullScreenContent(error: AdError) = callback.onShowFailed("AdMob ${error.code}: ${error.message}")
    }

    // ---- privacy --------------------------------------------------------------------------------

    private fun buildRequest(): AdRequest = AdMobPrivacy.buildRequest(settings.privacy)

    private fun applyRequestConfiguration(privacy: QartveloAdsPrivacy) {
        try {
            MobileAds.setRequestConfiguration(AdMobPrivacy.merge(MobileAds.getRequestConfiguration(), privacy))
        } catch (_: Throwable) {
            // Never let a Google SDK problem escape into the host app; requests use defaults then.
        }
    }

    private fun <T> ConcurrentHashMap<String, Loaded<T>>.fresh(key: String): Loaded<T>? {
        val entry = get(key) ?: return null
        if (entry.isFresh()) return entry
        remove(key, entry)
        return null
    }

    internal companion object {
        /** AdMob full-screen ads expire one hour after loading; stop offering them a little earlier. */
        const val AD_MAX_AGE_MS: Long = 55 * 60_000L
    }
}
