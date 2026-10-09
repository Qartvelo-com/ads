package com.qartvelo.admob

import android.content.Context
import android.os.Bundle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AgeRestrictedTreatment
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.qartvelo.sdk.QartveloAdsPrivacy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The AdMob banner size for a slot width: Google's standard anchored adaptive banner (50 to 90 dp
 * tall), the same size iOS uses. The large anchored variant is about twice as tall on phones.
 */
internal object AdMobBannerSize {
    fun forWidth(context: Context, widthDp: Int): AdSize =
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
}

/** Google's public test ad units, substituted for every placement in test mode. */
internal object AdMobUnits {
    enum class Format { BANNER, INTERSTITIAL, REWARDED }

    const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
    const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"

    /** The unit to request, or null when nothing usable is configured outside test mode. */
    fun resolve(format: Format, configured: String, testMode: Boolean): String? = when {
        testMode -> when (format) {
            Format.BANNER -> TEST_BANNER
            Format.INTERSTITIAL -> TEST_INTERSTITIAL
            Format.REWARDED -> TEST_REWARDED
        }
        configured.isBlank() -> null
        else -> configured.trim()
    }
}

/**
 * Maps QartveloAds privacy signals onto Google Mobile Ads. Signals only ever add restrictions; anything left
 * unspecified (null or false) keeps the publisher's own RequestConfiguration untouched.
 */
internal object AdMobPrivacy {
    fun merge(base: RequestConfiguration, privacy: QartveloAdsPrivacy): RequestConfiguration {
        val treatment = when {
            privacy.childDirected == true -> AgeRestrictedTreatment.CHILD
            privacy.underAgeOfConsent == true -> AgeRestrictedTreatment.TEEN
            else -> return base
        }
        // Never relax a stricter treatment the publisher configured directly with Google.
        if (base.ageRestrictedTreatment == AgeRestrictedTreatment.CHILD) return base
        return base.toBuilder().setAgeRestrictedTreatment(treatment).build()
    }

    /** `npa=1` requests non-personalized ads when the app reports that consent was refused. */
    fun requestExtras(privacy: QartveloAdsPrivacy): Bundle? =
        if (privacy.consentGiven == false) Bundle().apply { putString("npa", "1") } else null

    fun buildRequest(privacy: QartveloAdsPrivacy): AdRequest {
        val builder = AdRequest.Builder()
        requestExtras(privacy)?.let { builder.addNetworkExtrasBundle(AdMobAdapter::class.java, it) }
        return builder.build()
    }
}

/** Starts the Google Mobile Ads SDK once, on a background thread as Google recommends. */
internal object MobileAdsStarter {
    private val started = AtomicBoolean()

    fun start(appContext: Context) {
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                MobileAds.initialize(appContext)
            } catch (_: Throwable) {
                // A missing APPLICATION_ID or Play services problem must not crash the host app here.
            }
        }, "QartveloAds-admob-init").apply { isDaemon = true }.start()
    }
}
