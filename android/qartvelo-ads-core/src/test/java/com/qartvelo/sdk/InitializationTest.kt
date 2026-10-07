package com.qartvelo.sdk

import com.qartvelo.sdk.internal.FallbackDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InitializationTest : SdkTest() {

    @Test
    fun initializeIsIdempotent() {
        val results = mutableListOf<Boolean>()
        QartveloAds.initialize(app, APP_KEY, options()) { success, _ -> results.add(success) }
        QartveloAds.initialize(app, APP_KEY, options()) { success, _ -> results.add(success) }
        QartveloAds.initialize(app, "app_other_key", options(testMode = true)) { success, _ -> results.add(success) }
        awaitMain(message = "three init callbacks") { results.size == 3 }
        settle()

        assertEquals(listOf(true, true, true), results)
        assertTrue(QartveloAds.isInitialized())
        assertEquals("exactly one initialize request", 1, backend.count("/api/v1/sdk/initialize"))

        val body = backend.bodies("/api/v1/sdk/initialize").single()
        assertEquals(APP_KEY, body.getString("app_key"))
        assertEquals("android", body.getString("platform"))
        assertEquals(QartveloAds.SDK_VERSION, body.getString("sdk_version"))
        assertEquals(app.packageName, body.getString("package_name"))
        assertFalse(body.getBoolean("test_mode"))

        // A listener added after completion still gets the original outcome.
        var late: Boolean? = null
        QartveloAds.initialize(app, APP_KEY) { success, _ -> late = success }
        awaitMain { late != null }
        assertEquals(true, late)
    }

    @Test
    fun loadBeforeInitializeReportsNotInitialized() {
        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain { listener.has("loadFailed") }
        assertEquals(QartveloAdsErrorCode.NOT_INITIALIZED, listener.errors.single().code)
        assertFalse(QartveloAds.isInterstitialReady("game_end"))
    }

    @Test
    fun emptyAppKeyFailsWithoutCrashing() {
        var error: QartveloAdsError? = null
        QartveloAds.initialize(app, "  ", options()) { _, e -> error = e }
        awaitMain { error != null }
        assertEquals(QartveloAdsErrorCode.NOT_INITIALIZED, error!!.code)
        assertFalse(QartveloAds.isInitialized())
    }

    @Test
    fun rejectedAppKeyStillLeavesSdkUsable() {
        backend.initStatus = 401
        var error: QartveloAdsError? = null
        var success: Boolean? = null
        QartveloAds.initialize(app, APP_KEY, options()) { s, e -> success = s; error = e }
        awaitMain { success != null }
        assertEquals(false, success)
        assertEquals(QartveloAdsErrorCode.NOT_INITIALIZED, error!!.code)
        assertTrue("SDK runs in fallback-only mode", QartveloAds.isInitialized())

        QartveloAds.loadInterstitial("game_end", listener)
        awaitMain { listener.has("loadFailed") }
        assertTrue(listener.has("noAd:game_end"))
    }

    @Test
    fun offlineStartUsesCachedRemoteConfig() {
        // First process: the backend answers and the config (with its AdMob unit) is cached.
        assertTrue(init())
        settle()

        // "Next process": backend down, adapter present, no local AdMob mapping.
        QartveloAds.resetForTests()
        server.shutdown()
        QartveloAds.addEventListener(global)
        val adapter = FakeAdapter()
        assertFalse("initialize reports the network failure", init(adapter = adapter))
        assertTrue(QartveloAds.isInitialized())

        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertTrue(listener.toString(), listener.has("fallbackStarted:game_end:error"))
        assertTrue(listener.toString(), listener.has("loaded:game_end:ADMOB"))
        // The unit id came from the cached server config, not from options.
        assertEquals("game_end" to "ca-app-pub-test/game_end", adapter.loads.first())
    }

    @Test
    fun adapterDiscoveryNeverThrows() {
        assertNull(FallbackDiscovery.discover("com.qartvelo.admob.AdMobFallbackAdapter"))
        assertNull(FallbackDiscovery.discover("java.lang.String")) // not a FallbackAdapter
        assertNull(FallbackDiscovery.discover("java.lang.Runtime")) // private constructor
    }
}
