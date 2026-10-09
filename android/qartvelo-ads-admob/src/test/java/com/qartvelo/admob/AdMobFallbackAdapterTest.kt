package com.qartvelo.admob

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AgeRestrictedTreatment
import com.google.android.gms.ads.RequestConfiguration
import com.qartvelo.sdk.QartveloAdsPrivacy
import com.qartvelo.sdk.fallback.FallbackAdapter
import com.qartvelo.sdk.fallback.FallbackLoadCallback
import com.qartvelo.sdk.fallback.FallbackShowCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AdMobFallbackAdapterTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun isDiscoverableByReflectionLikeCoreDoes() {
        val instance = Class.forName("com.qartvelo.admob.AdMobFallbackAdapter").getDeclaredConstructor().newInstance()
        assertTrue(instance is FallbackAdapter)
        assertEquals("admob", (instance as FallbackAdapter).networkName)
    }

    @Test
    fun testModeSubstitutesGoogleTestUnits() {
        assertEquals(AdMobUnits.TEST_BANNER, AdMobUnits.resolve(AdMobUnits.Format.BANNER, "ca-app-pub-1/1", testMode = true))
        assertEquals(AdMobUnits.TEST_INTERSTITIAL, AdMobUnits.resolve(AdMobUnits.Format.INTERSTITIAL, "", testMode = true))
        assertEquals(AdMobUnits.TEST_REWARDED, AdMobUnits.resolve(AdMobUnits.Format.REWARDED, "x", testMode = true))
        assertEquals("ca-app-pub-1/2", AdMobUnits.resolve(AdMobUnits.Format.REWARDED, " ca-app-pub-1/2 ", testMode = false))
        assertNull("no unit, no request", AdMobUnits.resolve(AdMobUnits.Format.BANNER, " ", testMode = false))
    }

    @Test
    @Config(qualifiers = "w411dp-h923dp-xxhdpi")
    fun bannerUsesTheStandardAnchoredAdaptiveSize() {
        // Same size as iOS and the docs: a compact anchored banner, not Google's large variant.
        val size = AdMobBannerSize.forWidth(context, 411)
        assertEquals(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, 411), size)
        assertTrue("anchored banners stay 50 to 90 dp tall, got ${size.height} dp", size.height in 50..90)
    }

    @Test
    fun privacySignalsOnlyAddRestrictions() {
        val base = RequestConfiguration.Builder().build()
        assertSame("unknown signals leave the publisher configuration untouched", base, AdMobPrivacy.merge(base, QartveloAdsPrivacy()))
        assertSame(base, AdMobPrivacy.merge(base, QartveloAdsPrivacy(consentGiven = true, childDirected = false, underAgeOfConsent = false)))
        assertEquals(AgeRestrictedTreatment.CHILD, AdMobPrivacy.merge(base, QartveloAdsPrivacy(childDirected = true)).ageRestrictedTreatment)
        assertEquals(AgeRestrictedTreatment.TEEN, AdMobPrivacy.merge(base, QartveloAdsPrivacy(underAgeOfConsent = true)).ageRestrictedTreatment)

        val strict = RequestConfiguration.Builder().setAgeRestrictedTreatment(AgeRestrictedTreatment.CHILD).build()
        assertSame("never relaxes the publisher's stricter setting", strict, AdMobPrivacy.merge(strict, QartveloAdsPrivacy(underAgeOfConsent = true)))
    }

    @Test
    fun refusedConsentRequestsNonPersonalizedAds() {
        assertEquals("1", AdMobPrivacy.requestExtras(QartveloAdsPrivacy(consentGiven = false))!!.getString("npa"))
        assertNull("consent is never assumed or asserted", AdMobPrivacy.requestExtras(QartveloAdsPrivacy()))
        assertNull(AdMobPrivacy.requestExtras(QartveloAdsPrivacy(consentGiven = true)))
    }

    @Test
    fun missingUnitAndMissingAdFailCleanly() {
        val adapter = AdMobFallbackAdapter()
        var failure: String? = null
        adapter.loadInterstitial(context, "game_end", "", object : FallbackLoadCallback {
            override fun onLoaded() = throw AssertionError("must not load")
            override fun onFailed(message: String) { failure = message }
        })
        assertTrue(failure!!.contains("no AdMob interstitial unit"))
        assertFalse(adapter.isInterstitialReady("game_end"))
        assertFalse(adapter.isRewardedReady("reward_coins"))

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var showFailure: String? = null
        adapter.showRewarded(activity, "reward_coins", object : FallbackShowCallback {
            override fun onShowFailed(message: String) { showFailure = message }
            override fun onReward(type: String, amount: Int) = throw AssertionError("no reward without an ad")
        })
        assertTrue(showFailure!!.contains("no AdMob rewarded ad loaded"))
    }
}
