package com.qartvelo.sdk

import android.content.pm.ApplicationInfo
import com.qartvelo.sdk.internal.Emulator
import com.qartvelo.sdk.internal.FallbackDiscovery
import com.qartvelo.sdk.internal.TestHooks
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
        assertFalse(body.getBoolean("is_emulator"))

        // A listener added after completion still gets the original outcome.
        var late: Boolean? = null
        QartveloAds.initialize(app, APP_KEY) { success, _ -> late = success }
        awaitMain { late != null }
        assertEquals(true, late)
    }

    @Test
    fun debuggableBuildsAreInTestModeUnlessOptedOut() {
        val info = app.applicationInfo
        val original = info.flags
        try {
            info.flags = original or ApplicationInfo.FLAG_DEBUGGABLE
            assertTrue(init(options(testModeInDebugBuilds = true)))
            QartveloAds.loadInterstitial("game_end", listener)
            awaitMain(message = "ad request") { backend.count("/api/v1/ads/request") == 1 }
            assertTrue(backend.bodies("/api/v1/sdk/initialize").single().getBoolean("test_mode"))
            assertTrue(backend.bodies("/api/v1/ads/request").single().getBoolean("test_mode"))

            QartveloAds.resetForTests()
            assertTrue("opted out", init(options(testModeInDebugBuilds = false)))
            assertFalse(backend.bodies("/api/v1/sdk/initialize").last().getBoolean("test_mode"))

            QartveloAds.resetForTests()
            info.flags = original and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertTrue("release build", init(options(testModeInDebugBuilds = true)))
            assertFalse(backend.bodies("/api/v1/sdk/initialize").last().getBoolean("test_mode"))
        } finally {
            info.flags = original
        }
    }

    @Test
    fun emulatorsAreAlwaysInTestModeAndReportedToTheBackend() {
        TestHooks.emulator = true
        assertTrue(init(options(testMode = false, testModeInDebugBuilds = false)))

        val body = backend.bodies("/api/v1/sdk/initialize").single()
        assertTrue(body.getBoolean("test_mode"))
        assertTrue(body.getBoolean("is_emulator"))
    }

    @Test
    fun emulatorDetectionMatchesEmulatorsButNotRealPhones() {
        // Android Studio emulator (API 34), an older x86 image, Genymotion.
        assertTrue(Emulator.detect("google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.036/10813309:userdebug/dev-keys", "ranchu", "sdk_gphone64_x86_64", "sdk_gphone64_x86_64", "Google", "google", "emu64xa"))
        assertTrue(Emulator.detect("generic_x86/sdk_x86/generic_x86:9/PSR1.180720.075/5124027:user/release-keys", "goldfish", "sdk_x86", "Android SDK built for x86", "unknown", "generic_x86", "generic_x86"))
        assertTrue(Emulator.detect("google/vbox86p/vbox86p:11/RQ1A.210105.003/1:userdebug/test-keys", "vbox86", "vbox86p", "Google Pixel 3", "Genymotion", "google", "vbox86p"))
        // Pixel 8, Galaxy S23, Xiaomi.
        assertFalse(Emulator.detect("google/shiba/shiba:14/AP1A.240305.019.A1/11445699:user/release-keys", "shiba", "shiba", "Pixel 8", "Google", "google", "shiba"))
        assertFalse(Emulator.detect("samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXU3CWK3:user/release-keys", "qcom", "dm1qxxx", "SM-S911B", "samsung", "samsung", "dm1q"))
        assertFalse(Emulator.detect("Redmi/sunny_global/sunny:13/TKQ1.221114.001/V14.0.4.0.TKGMIXM:user/release-keys", "qcom", "sunny_global", "M2101K7AG", "Xiaomi", "Redmi", "sunny"))
        assertFalse("Robolectric is not an emulator", Emulator.current())
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
