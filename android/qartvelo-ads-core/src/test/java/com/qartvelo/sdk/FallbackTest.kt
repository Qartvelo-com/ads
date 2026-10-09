package com.qartvelo.sdk

import android.content.pm.ApplicationInfo
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

class FallbackTest : SdkTest() {

    @Test
    fun noFillFallsBackToPreloadedAdMob() {
        val adapter = FakeAdapter()
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")

        assertEquals(listOf("fallbackStarted:game_end:no_fill", "loaded:game_end:ADMOB"), listener.events)
        assertEquals("AdMob preloaded once, with the server-side unit", listOf("game_end" to "ca-app-pub-test/game_end"), adapter.loads)
        assertTrue(QartveloAds.isInterstitialReady("game_end"))
        awaitMain(message = "fallback telemetry") { backend.count("/api/v1/events/fallback") == 1 }
        val telemetry = backend.bodies("/api/v1/events/fallback").single()
        assertEquals("game_end", telemetry.getString("placement"))
        assertEquals("no_fill", telemetry.getString("reason"))

        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        awaitMain { listener.has("dismissed") }
        assertEquals(listOf("i:game_end"), adapter.shows)
        assertNull("no QartveloAds activity for a fallback show", shadowOf(host).nextStartedActivity)
        assertEquals(
            listOf("shown:game_end:ADMOB", "impression:game_end:ADMOB", "dismissed:game_end:ADMOB"),
            listener.events.drop(2),
        )
        assertEquals(0, listener.offMainThread)
    }

    @Test
    fun localAdMobMappingWinsOverServerMapping() {
        val adapter = FakeAdapter()
        assertTrue(init(options(units = mapOf("game_end" to "ca-app-pub-mine/42")), adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals("ca-app-pub-mine/42", adapter.loads.single().second)
    }

    @Test
    fun serverFallbackNoneSkipsAdMob() {
        val adapter = FakeAdapter()
        backend.placements = listOf(Placement("game_end", "interstitial", fallbackProvider = "none"))
        backend.adResponses.add(backend.noFill(fallback = "none"))
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("noAd:game_end", "loadFailed:game_end:NO_FILL"), listener.events)
        assertTrue(adapter.loads.isEmpty())
    }

    @Test
    fun timeoutFallsBackWithoutWaitingForQartveloAds() {
        val adapter = FakeAdapter()
        backend.placements = listOf(Placement("game_end", "interstitial", timeoutMs = 300))
        backend.adResponses.add(backend.fill("interstitial"))
        backend.adDelayMs = 3_000
        // The remote per-placement timeout (300 ms) wins over the local option.
        assertTrue(init(options(timeoutMs = 5_000), adapter))

        val started = System.currentTimeMillis()
        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain(timeoutMs = 2_500, message = "timeout fallback") { listener.has("loaded") || listener.has("loadFailed") }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("fell back after ${elapsed}ms", elapsed < 2_000)
        assertEquals(listOf("fallbackStarted:game_end:timeout", "loaded:game_end:ADMOB"), listener.events)
    }

    @Test
    fun adapterAbsentReportsNoAdAvailableCleanly() {
        // qartvelo-ads-core alone: com.qartvelo.admob.AdMobFallbackAdapter is not on the test classpath.
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("noAd:game_end", "loadFailed:game_end:NO_FILL"), listener.events)
        assertEquals(QartveloAdsErrorCode.NO_FILL, listener.errors.single().code)
        assertEquals("no telemetry when no fallback is attempted", 0, backend.count("/api/v1/events/fallback"))

        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        awaitMain { listener.count("noAd") == 2 }
    }

    @Test
    fun networkFailureFallsBackAndNeverCrashes() {
        val adapter = FakeAdapter()
        assertTrue(init(adapter = adapter))
        backend.adResponses.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("fallbackStarted:game_end:error", "loaded:game_end:ADMOB"), listener.events)

        // Backend completely gone and no fallback available: a clean NETWORK_ERROR.
        server.shutdown()
        val rewarded = RecordingListener("rewarded")
        adapter.loadSucceeds = false
        loadAndWait(AdFormat.REWARDED, "reward_coins", rewarded)
        assertEquals(
            listOf("fallbackStarted:reward_coins:error", "noAd:reward_coins", "loadFailed:reward_coins:NETWORK_ERROR"),
            rewarded.events,
        )
    }

    @Test
    fun serverErrorIsIsolated() {
        assertTrue(init())
        backend.adResponses.add(backend.error(500, "server_error"))
        backend.adResponses.add(MockResponse().setBody("<html>not json</html>"))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(QartveloAdsErrorCode.NETWORK_ERROR, listener.errors.single().code)
        val second = RecordingListener("second")
        loadAndWait(AdFormat.INTERSTITIAL, "game_end", second)
        assertEquals(QartveloAdsErrorCode.NETWORK_ERROR, second.errors.single().code)
    }

    @Test
    fun creativeFailureFallsBack() {
        val adapter = FakeAdapter()
        backend.adResponses.add(backend.fill("interstitial", creativePath = "/creatives/missing.png"))
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("fallbackStarted:game_end:creative_failed", "loaded:game_end:ADMOB"), listener.events)
        assertEquals(1, backend.count("/creatives/missing.png"))

        // Without an adapter the same failure is reported as CREATIVE_FAILED.
        val noAdapter = RecordingListener("noAdapter")
        adapter.loadSucceeds = false
        backend.creatives["/creatives/html.png"] = MockResponse().setBody("<html>oops</html>")
        backend.adResponses.add(backend.fill("rewarded", creativeType = "image", creativePath = "/creatives/html.png"))
        loadAndWait(AdFormat.REWARDED, "reward_coins", noAdapter)
        assertTrue(noAdapter.toString(), noAdapter.has("noAd:reward_coins"))
        assertEquals(QartveloAdsErrorCode.CREATIVE_FAILED, noAdapter.errors.single().code)
        assertFalse(QartveloAds.isRewardedReady("reward_coins"))
    }

    @Test
    fun expiredAdIsNeverShown() {
        val adapter = FakeAdapter(loadSucceeds = false)
        backend.adResponses.add(backend.fill("interstitial", expiresInMs = 60_000))
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertTrue(QartveloAds.isInterstitialReady("game_end"))

        advance(2 * 60_000)
        assertFalse("expired ads are not ready", QartveloAds.isInterstitialReady("game_end"))
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        awaitMain { listener.has("noAd") }
        assertNull("QartveloAds activity must not start for an expired ad", shadowOf(host).nextStartedActivity)
        assertEquals(0, backend.count("/api/v1/events/impression"))
    }

    @Test
    fun alreadyExpiredResponseIsDiscarded() {
        val adapter = FakeAdapter()
        backend.adResponses.add(backend.fill("interstitial", expiresInMs = 1_000))
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("fallbackStarted:game_end:error", "loaded:game_end:ADMOB"), listener.events)
        assertEquals("creative not even downloaded", 0, backend.count("/creatives/i.png"))
    }

    @Test
    fun expiredSessionIsRefreshedAndRetriedOnce() {
        backend.adResponses.add(backend.error(401, "session_expired"))
        backend.adResponses.add(backend.fill("interstitial"))
        assertTrue(init(options(timeoutMs = 3_000)))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("loaded:game_end:QARTVELO"), listener.events)
        assertEquals(2, backend.count("/api/v1/sdk/initialize"))
        assertEquals(2, backend.count("/api/v1/ads/request"))
        val tokens = backend.bodies("/api/v1/ads/request").map { it.getString("session_token") }
        assertTrue("retry used the new session", tokens[0] != tokens[1])
    }

    @Test
    fun killSwitchSkipsQartveloAdsRequest() {
        val adapter = FakeAdapter()
        backend.placements = listOf(Placement("game_end", "interstitial", qartveloEnabled = false))
        assertTrue(init(adapter = adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("fallbackStarted:game_end:disabled", "loaded:game_end:ADMOB"), listener.events)
        assertEquals(0, backend.count("/api/v1/ads/request"))
    }

    @Test
    fun localFallbackSwitchDisablesAdMob() {
        val adapter = FakeAdapter()
        assertTrue(init(options(admobFallback = false), adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("noAd:game_end", "loadFailed:game_end:NO_FILL"), listener.events)
        assertTrue(adapter.loads.isEmpty())
    }

    @Test
    fun testModeFallsBackEvenWithoutUnitMapping() {
        val adapter = FakeAdapter()
        backend.placements = listOf(Placement("game_end", "interstitial", admobUnit = null))
        assertTrue(init(options(testMode = true), adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertTrue(listener.has("loaded:game_end:ADMOB"))
        assertEquals("adapter substitutes its test unit for the empty id", "", adapter.loads.single().second)
        assertEquals(true, adapter.settings?.testMode)
    }

    @Test
    fun debuggableBuildsGiveTheAdapterTestUnitsUnlessOptedOut() {
        val info = app.applicationInfo
        val original = info.flags
        try {
            info.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            val debug = FakeAdapter()
            assertTrue(init(options(admobTestUnitsInDebugBuilds = true), debug))
            assertFalse("Qartvelo test mode stays off", backend.bodies("/api/v1/sdk/initialize").last().getBoolean("test_mode"))
            assertEquals(true, debug.settings?.testMode)

            QartveloAds.resetForTests()
            val optedOut = FakeAdapter()
            assertTrue(init(options(admobTestUnitsInDebugBuilds = false), optedOut))
            assertEquals(false, optedOut.settings?.testMode)

            QartveloAds.resetForTests()
            info.flags = original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            val release = FakeAdapter()
            assertTrue(init(options(admobTestUnitsInDebugBuilds = true), release))
            assertEquals(false, release.settings?.testMode)
        } finally {
            info.flags = original
        }
    }
}
