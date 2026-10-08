package com.qartvelo.sdk

import android.content.Intent
import android.view.View
import android.widget.ImageView
import com.qartvelo.sdk.internal.QartveloAdsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class InterstitialLifecycleTest : SdkTest() {

    @Test
    fun fillLoadShowImpressionClickDismiss() {
        backend.adResponses.add(backend.fill("interstitial"))
        assertTrue(init())

        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("loaded:game_end:QARTVELO"), listener.events)
        val info = listener.infos.single()
        assertEquals("cmp_12", info.campaignId)
        assertEquals("cr_34", info.creativeId)
        assertTrue(QartveloAds.isInterstitialReady("game_end"))

        val request = backend.bodies("/api/v1/ads/request").single()
        assertEquals("game_end", request.getString("placement"))
        assertEquals("interstitial", request.getString("format"))
        assertTrue(request.getString("session_token").startsWith("sess-"))
        assertEquals(1, backend.count("/creatives/i.png"))

        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end")
        val adActivity = launchedAdActivity(host)
        awaitMain(message = "impression") { listener.has("impression") }
        assertEquals(listOf("loaded:game_end:QARTVELO", "shown:game_end:QARTVELO", "impression:game_end:QARTVELO"), listener.events)
        assertFalse("one ad, one show", QartveloAds.isInterstitialReady("game_end"))
        awaitMain(message = "impression event sent") { backend.count("/api/v1/events/impression") == 1 }
        val impression = backend.bodies("/api/v1/events/impression").single()
        assertEquals("req_1", impression.getString("request_id"))
        assertEquals("imp-token-1", impression.getString("impression_token"))

        // Click: the event is queued (after the impression) before the browser intent is fired.
        val root = adActivity.get().window.decorView
        val image = findImage(root)
        image.performClick()
        awaitMain(message = "click event") { backend.count("/api/v1/events/click") == 1 }
        val opened: Intent = shadowOf(adActivity.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("https://advertiser.example/landing", opened.dataString)
        image.performClick()
        settle()
        assertEquals("click recorded once", 1, backend.count("/api/v1/events/click"))
        val events = backend.paths().filter { it.startsWith("/api/v1/events/") }
        assertEquals(listOf("/api/v1/events/impression", "/api/v1/events/click"), events)

        // Close appears after the delay, then dismiss.
        val close = root.findByDescription("Close ad")!!
        assertEquals(View.GONE, close.visibility)
        advance(QartveloAdsActivity.IMAGE_CLOSE_DELAY_MS + 50)
        assertEquals(View.VISIBLE, close.visibility)
        close.performClick()
        awaitMain { listener.has("dismissed") }
        assertTrue(adActivity.get().isFinishing)
        assertEquals(1, listener.count("clicked"))
        assertEquals(1, listener.count("dismissed"))
        assertEquals(0, listener.offMainThread + global.offMainThread)
        assertEquals("global observer sees the same events", listener.events, global.events)
    }

    @Test
    fun testAdsAreLabelledTestAd() {
        backend.adResponses.add(backend.fill("interstitial", test = true))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end")
        val decor = launchedAdActivity(host).get().window.decorView
        awaitMain(message = "impression") { listener.has("impression") }

        assertNotNull(decor.findByText("Test ad"))
        assertNull(decor.findByText("Ad"))
    }

    @Test
    fun adBadgeOpensQartveloSiteWithRefAndIsNotAClick() {
        backend.adResponses.add(backend.fill("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end")
        val adActivity = launchedAdActivity(host)
        awaitMain(message = "impression") { listener.has("impression") }

        adActivity.get().window.decorView.findByDescription("Ad. About Qartvelo Ads")!!.performClick()
        val opened: Intent = shadowOf(adActivity.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("${backend.baseUrl}?ref=${host.packageName}", opened.dataString)

        settle()
        assertEquals(0, backend.count("/api/v1/events/click"))
        assertFalse(listener.has("clicked"))
        assertFalse("the ad stays on screen", adActivity.get().isFinishing)
    }

    @Test
    fun concurrentLoadsJoinTheInFlightLoad() {
        backend.adResponses.add(backend.fill("interstitial"))
        backend.adDelayMs = 200
        assertTrue(init(options(timeoutMs = 3_000)))
        val second = RecordingListener("second")
        QartveloAds.loadInterstitial("game_end", listener)
        QartveloAds.loadInterstitial("game_end", second)
        awaitMain { listener.has("loaded") && second.has("loaded") }
        settle()
        assertEquals(1, backend.count("/api/v1/ads/request"))
        assertEquals(1, listener.count("loaded:game_end:QARTVELO"))
        assertEquals(1, second.count("loaded:game_end:QARTVELO"))
        assertEquals("global observers get one event per load", 1, global.count("loaded"))

        // A cached valid ad answers further loads without a request.
        val third = RecordingListener("third")
        loadAndWait(AdFormat.INTERSTITIAL, "game_end", third)
        assertEquals(1, backend.count("/api/v1/ads/request"))
        assertTrue(third.has("loaded:game_end:QARTVELO"))
    }

    @Test
    fun showWithoutLoadReportsNoAdAvailable() {
        assertTrue(init())
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        awaitMain { listener.has("noAd") }
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test
    fun secondShowWhileShowingIsRejected() {
        backend.adResponses.add(backend.fill("interstitial"))
        backend.adResponses.add(backend.fill("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        launchedAdActivity(host)
        awaitMain { listener.has("impression") }

        val other = RecordingListener("other")
        QartveloAds.showInterstitial(host, "game_end", other)
        awaitMain { other.has("loadFailed") }
        assertEquals(QartveloAdsErrorCode.ALREADY_SHOWING, other.errors.single().code)
    }

    @Test
    fun formatMismatchIsInvalidPlacement() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "home_banner")
        assertEquals(QartveloAdsErrorCode.INVALID_PLACEMENT, listener.errors.single().code)
        assertEquals(0, backend.count("/api/v1/ads/request"))
    }

    @Test
    fun testModeFlagsAreSent() {
        assertTrue(init(options(testMode = true, forceNoFill = true)))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val init = backend.bodies("/api/v1/sdk/initialize").single()
        assertTrue(init.getBoolean("test_mode"))
        val request = backend.bodies("/api/v1/ads/request").single()
        assertTrue(request.getBoolean("test_mode"))
        assertTrue(request.getBoolean("test_force_no_fill"))
    }

    private fun findImage(root: View): ImageView {
        val found = root.findByDescription("Ad")
        assertNotNull("creative view", found)
        return found as ImageView
    }
}
