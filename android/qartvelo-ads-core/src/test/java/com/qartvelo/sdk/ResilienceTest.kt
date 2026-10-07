package com.qartvelo.sdk

import com.qartvelo.sdk.internal.Engine
import com.qartvelo.sdk.internal.FullscreenController
import com.qartvelo.sdk.internal.QartveloAdsActivity
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Behaviour across restarts, outages and slow infrastructure: test mode never sticks, a hanging
 * backend or CDN never holds a load past its budget, kill switches reach running apps, and
 * listeners are not retained.
 */
class ResilienceTest : SdkTest() {

    /** Simulates the next process start: same app data (SharedPreferences), fresh SDK state. */
    private fun restartProcess() {
        settle()
        QartveloAds.resetForTests()
        QartveloAds.addEventListener(global)
        backend.requests.clear()
    }

    @Test
    fun testModeDoesNotStickAfterTheOptionIsTurnedOff() {
        assertTrue(init(options(testMode = true), FakeAdapter()))

        restartProcess()
        val adapter = FakeAdapter()
        assertTrue(init(options(testMode = false), adapter))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")

        assertFalse(backend.bodies("/api/v1/sdk/initialize").single().getBoolean("test_mode"))
        assertFalse(backend.bodies("/api/v1/ads/request").single().getBoolean("test_mode"))
        assertEquals("AdMob uses the publisher's real units again", false, adapter.settings?.testMode)
    }

    @Test
    fun hangingBackendAtStartupFallsBackWithinTheRequestTimeout() {
        assertTrue(init()) // Caches the remote config, as any earlier run would.

        restartProcess()
        backend.initDelayMs = 3_000
        val adapter = FakeAdapter()
        QartveloAds.registerFallbackAdapter(adapter)
        QartveloAds.initialize(app, APP_KEY, options())
        val started = System.currentTimeMillis()
        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain(timeoutMs = 2_500, message = "load outcome") { listener.has("loaded") || listener.has("loadFailed") }

        val elapsed = System.currentTimeMillis() - started
        assertTrue("fell back after ${elapsed}ms instead of waiting for /sdk/initialize", elapsed < 1_500)
        assertEquals(listOf("fallbackStarted:game_end:timeout", "loaded:game_end:ADMOB"), listener.events)
        assertEquals("AdMob preloaded with the cached unit", "ca-app-pub-test/game_end", adapter.loads.first().second)
        assertFalse("initialization is still running", QartveloAds.isInitialized())
    }

    @Test
    fun killSwitchTurnedBackOnReachesARunningApp() {
        backend.servingEnabled = false
        assertTrue(init(adapter = FakeAdapter()))
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertEquals(listOf("fallbackStarted:game_end:disabled", "loaded:game_end:ADMOB"), listener.events)
        assertEquals(0, backend.count("/api/v1/ads/request"))

        // An admin switches QartveloAds back on. Once the "off" config is stale, the next load asks the
        // backend (which applies every kill switch itself) and the config is refreshed.
        backend.servingEnabled = true
        backend.adResponses.add(backend.fill("interstitial"))
        advance(Engine.DISABLED_RECHECK_MS + 1_000)
        val next = RecordingListener("next")
        loadAndWait(AdFormat.INTERSTITIAL, "game_end", next)

        assertEquals(listOf("loaded:game_end:QARTVELO"), next.events)
        awaitMain(message = "config refresh") { backend.count("/api/v1/sdk/initialize") == 2 }
    }

    @Test
    fun slowCreativeLetsAReadyFallbackFinishTheLoadAndIsKeptForTheNextShow() {
        backend.creatives["/creatives/i.png"] = MockResponse()
            .setBody(Buffer().write(pngBytes(108, 192)))
            .setHeader("Content-Type", "image/png")
            .setBodyDelay(2, TimeUnit.SECONDS)
        backend.adResponses.add(backend.fill("interstitial"))
        val adapter = FakeAdapter()
        assertTrue(init(adapter = adapter))

        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain(message = "creative download started") { backend.count("/creatives/i.png") == 1 }
        settle(100)
        assertTrue("QartveloAds answered in time, so the load still waits: $listener", listener.events.isEmpty())

        advance(800 + FullscreenController.CREATIVE_GRACE_MS)
        awaitMain(message = "fallback finishes the load") { listener.has("loaded") }
        assertEquals(listOf("fallbackStarted:game_end:timeout", "loaded:game_end:ADMOB"), listener.events)

        // The download continued in the background; the next show prefers the QartveloAds ad.
        val cacheDir = File(app.cacheDir, "qartvelo_creatives")
        awaitMain(timeoutMs = 6_000, message = "creative cached") { cacheDir.listFiles()?.any { it.name.endsWith(".png") } == true }
        settle()
        val host = hostActivity().get()
        QartveloAds.showInterstitial(host, "game_end", listener)
        settle()
        assertEquals(QartveloAdsActivity::class.java.name, shadowOf(host).nextStartedActivity?.component?.className)
        assertTrue("the AdMob ad stays ready for later", adapter.shows.isEmpty())
    }

    /** Loads with a listener that nothing else references, like an Activity that is later destroyed. */
    private fun loadWithThrowawayListener(): WeakReference<QartveloAdsListener> {
        val activityLike = RecordingListener("activity")
        QartveloAds.loadInterstitial("game_end", activityLike)
        return WeakReference(activityLike)
    }

    @Test
    fun loadListenerIsNotRetainedOnceTheLoadFinished() {
        assertTrue(init(adapter = FakeAdapter()))
        val ref = loadWithThrowawayListener()
        awaitMain(message = "load outcome") { global.has("loaded:game_end") }
        settle()

        repeat(20) {
            if (ref.get() != null) {
                System.gc()
                Thread.sleep(20)
            }
        }
        assertNull("an Activity passed as a load listener must be collectable", ref.get())
    }
}
