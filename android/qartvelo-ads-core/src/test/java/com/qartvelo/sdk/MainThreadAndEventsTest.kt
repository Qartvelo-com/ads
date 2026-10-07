package com.qartvelo.sdk

import com.qartvelo.sdk.internal.QartveloAdsActivity
import com.qartvelo.sdk.internal.RemoteConfig
import com.qartvelo.sdk.internal.parseIsoMillis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainThreadAndEventsTest : SdkTest() {

    @Test
    fun everyCallbackArrivesOnTheMainThreadEvenWhenCalledFromBackground() {
        val adapter = FakeAdapter(callbackFromBackground = true)
        backend.adResponses.add(backend.fill("interstitial"))
        // Public API used from a worker thread.
        val done = CountDownLatch(1)
        Thread {
            QartveloAds.registerFallbackAdapter(adapter)
            QartveloAds.initialize(app, APP_KEY, options())
            done.countDown()
        }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        awaitMain { QartveloAds.isInitialized() }

        val worker = Thread {
            QartveloAds.loadInterstitial("game_end", listener)
            QartveloAds.loadRewarded("reward_coins", listener)
        }
        worker.start()
        worker.join()
        awaitMain { listener.has("loaded:game_end") && listener.has("loaded:reward_coins") }
        val host = hostActivity().get()
        QartveloAds.showRewarded(host, "reward_coins", listener)
        awaitMain { listener.has("dismissed:reward_coins") }

        assertTrue(listener.events.size >= 6)
        assertEquals("listener callbacks off main: $listener", 0, listener.offMainThread)
        assertEquals("global callbacks off main", 0, global.offMainThread)
    }

    @Test
    fun callbacksAreNeverDeliveredSynchronouslyInsideTheCall() {
        assertTrue(init())
        val host = hostActivity().get()
        QartveloAds.loadInterstitial("game_end", listener)
        QartveloAds.showInterstitial(host, "game_end", listener)
        assertTrue("delivered later, via the main looper", listener.events.isEmpty())
        awaitMain { listener.has("noAd:game_end") }
    }

    @Test
    fun eventsAreRetriedSeriallyWithImpressionBeforeClick() {
        backend.eventStatuses["/api/v1/events/impression"] = mutableListOf(500, 503)
        backend.eventStatuses["/api/v1/events/click"] = mutableListOf(409)
        backend.adResponses.add(backend.fill("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        val ad = launchedAdActivity(host)
        awaitMain { listener.has("impression") }
        ad.get().window.decorView.findByDescription("Ad")!!.performClick()

        awaitMain(message = "click delivered") { backend.count("/api/v1/events/click") == 1 }
        settle()
        val events = backend.paths().filter { it.startsWith("/api/v1/events/") && !it.endsWith("fallback") }
        assertEquals(
            "impression retried after 5xx and delivered before the click; 409 is not retried",
            listOf("/api/v1/events/impression", "/api/v1/events/impression", "/api/v1/events/impression", "/api/v1/events/click"),
            events,
        )
        advance(QartveloAdsActivity.IMAGE_CLOSE_DELAY_MS + 50)
    }

    @Test
    fun wireParsing() {
        assertEquals(1_791_398_400_000L, parseIsoMillis("2026-10-07T18:40:00Z"))
        assertEquals(1_791_398_400_123L, parseIsoMillis("2026-10-07T18:40:00.123456Z"))
        assertEquals(1_791_398_400_000L, parseIsoMillis("2026-10-07T22:40:00+04:00"))
        assertEquals(1_791_398_400_000L, parseIsoMillis("2026-10-07T22:40:00+0400"))
        assertNull(parseIsoMillis("yesterday"))
        assertNull(parseIsoMillis(null))

        val config = RemoteConfig.parse(JSONObject("""
            {"config":{"serving_enabled":false,"fallback_enabled":true},
             "placements":[{"code":"b","format":"banner","banner_refresh_seconds":5,"admob_ad_unit_id":null},
                           {"code":"i","format":"interstitial","request_timeout_ms":250,"ourads_enabled":false}]}
        """.trimIndent()))
        assertFalse(config.servingEnabled)
        assertEquals("refresh is clamped to the 30 s minimum", 30, config.placements["b"]!!.bannerRefreshSeconds)
        assertNull(config.placements["b"]!!.admobAdUnitId)
        assertEquals(250L, config.placements["i"]!!.requestTimeoutMs)
        assertFalse(config.placements["i"]!!.qartveloEnabled)
        assertEquals("admob", config.placements["i"]!!.fallbackProvider)
    }
}
