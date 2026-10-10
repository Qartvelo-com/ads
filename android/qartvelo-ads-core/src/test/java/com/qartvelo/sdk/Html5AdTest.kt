package com.qartvelo.sdk

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.qartvelo.sdk.internal.Html5Surface
import com.qartvelo.sdk.internal.ServedAd
import com.qartvelo.sdk.internal.TestHooks
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** HTML5 ads: request, bundle download, and the banner and interstitial rules with a fake web view. */
@Config(qualifiers = "w400dp-h900dp-xxhdpi")
class Html5AdTest : SdkTest() {
    private val surfaces = CopyOnWriteArrayList<FakeSurface>()

    internal class FakeSurface(context: Context, val ad: ServedAd) : Html5Surface {
        override val view: View = View(context)
        var onReady: (() -> Unit)? = null
        var onFailed: ((String) -> Unit)? = null
        var onClick: (() -> Unit)? = null
        var paused = false
        var destroyed = false

        override fun load(onReady: () -> Unit, onFailed: (String) -> Unit, onClick: () -> Unit) {
            this.onReady = onReady
            this.onFailed = onFailed
            this.onClick = onClick
        }

        override fun pause() { paused = true }
        override fun resume() { paused = false }
        override fun destroy() { destroyed = true }
    }

    @Before
    fun setUpHtml5() {
        backend.html5Bundle()
        TestHooks.html5SurfaceFactory = { context, ad -> FakeSurface(context, ad).also { surfaces.add(it) } }
    }

    // ---- request and bundle download (plan Task 2) -----------------------------------------------

    @Test
    fun bannersAndInterstitialsOfferHtml5RewardedDoesNot() {
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        loadAndWait(AdFormat.REWARDED, "reward_coins")
        val (interstitial, rewarded) = backend.bodies("/api/v1/ads/request")
        assertEquals("[\"image\",\"video\",\"html5\"]", interstitial.getJSONArray("supported_creative_types").toString())
        assertEquals("[\"image\",\"video\"]", rewarded.getJSONArray("supported_creative_types").toString())
    }

    @Test
    fun anHtml5FillDownloadsTheServedLayoutsFiles() {
        backend.adResponses.add(backend.fillHtml5("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")

        assertTrue(listener.toString(), listener.has("loaded:game_end:QARTVELO"))
        for (file in listOf("index.html", "style.css", "main.js", "m/a.png")) assertEquals(file, 1, backend.count("/creatives/h5/$file"))
        assertEquals("other layouts load lazily", 0, backend.count("/creatives/h5/m/b.png"))
        val dir = File(app.cacheDir, "qartvelo_creatives").listFiles()!!.single { it.isDirectory }
        assertTrue(File(dir, "m/a.png").isFile)
    }

    @Test
    fun unsafeOrMissingFilesAreACreativeFailure() {
        for (files in listOf(listOf("index.html", "../x.js"), listOf("index.html", "/abs.js"), listOf("index.html", "https://evil.example/x.js"), listOf("main.js"), emptyList())) {
            backend.adResponses.add(backend.fillHtml5("interstitial", files = files))
        }
        backend.adResponses.add(backend.fillHtml5("interstitial", files = listOf("index.html", "missing.js")))
        assertTrue(init())
        repeat(6) {
            listener.events.clear()
            QartveloAds.loadInterstitial("game_end", listener)
            awaitMain(message = "outcome $it") { listener.has("loadFailed") || listener.has("loaded") || listener.has("fallbackStarted") }
            settle(100)
            assertFalse("attempt $it: $listener", listener.has("loaded:game_end:QARTVELO"))
        }
    }

    @Test
    fun aBundleOverTwoMegabytesIsRefused() {
        backend.creatives["/creatives/h5/m/a.png"] = MockResponse().setBody(Buffer().write(ByteArray(2 * 1024 * 1024 + 10)))
        backend.adResponses.add(backend.fillHtml5("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        assertFalse(listener.toString(), listener.has("loaded:game_end:QARTVELO"))
    }

    // ---- banners (plan Task 3) -------------------------------------------------------------------

    private lateinit var host: ActivityController<Activity>
    private lateinit var container: FrameLayout

    @Before
    fun setUpHost() {
        host = hostActivity()
        container = FrameLayout(host.get())
        host.get().setContentView(container)
        // Robolectric leaves the window "not app-visible"; drive the framework's path (see BannerLifecycleTest).
        val decor = host.get().window.decorView
        val root = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(decor)!!
        root.javaClass.getDeclaredMethod("dispatchAppVisibility", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(root, true)
        settle(20)
    }

    private fun showBanner(configure: QartveloAdsBannerView.() -> Unit = {}): QartveloAdsBannerView {
        val banner = QartveloAdsBannerView(host.get()).apply {
            placementId = "home_banner"
            listener = this@Html5AdTest.listener
            configure()
            container.addView(this)
        }
        banner.load()
        awaitMain(message = "surface created") { surfaces.isNotEmpty() && surfaces.last().onReady != null }
        settle()
        return banner
    }

    @Test
    fun aBannerIsShownAndCountedOnlyOnceReady() {
        backend.placements = listOf(Placement("home_banner", "banner", timeoutMs = 10_000))
        backend.adResponses.add(backend.fillHtml5("banner"))
        assertTrue(init())
        val banner = showBanner()
        val surface = surfaces.single()

        assertFalse("not loaded before ready", listener.has("loaded"))
        assertEquals("loads hidden in the slot", 0f, (surface.view.parent as View).alpha)

        surface.onClick!!()
        surface.onReady!!()
        awaitMain(message = "impression") { listener.has("impression:home_banner:QARTVELO") }
        assertEquals(listOf("loaded:home_banner:QARTVELO", "shown:home_banner:QARTVELO", "impression:home_banner:QARTVELO"), listener.events)
        assertEquals("anchored slot: the web view fills it", 1f, (surface.view.parent as View).alpha)
        assertTrue(banner.indexOfChild(surface.view.parent as View) >= 0)

        surface.onClick!!()
        surface.onClick!!()
        awaitMain(message = "click event") { backend.count("/api/v1/events/click") == 1 }
        settle()
        assertEquals("one click per impression; none before it", 1, backend.count("/api/v1/events/click"))
        assertEquals(1, listener.count("clicked"))
    }

    @Test
    fun aBannerThatNeverGetsReadyFallsBack() {
        backend.placements = listOf(Placement("home_banner", "banner", timeoutMs = 10_000))
        backend.adResponses.add(backend.fillHtml5("banner"))
        val adapter = FakeAdapter()
        assertTrue(init(adapter = adapter))
        showBanner()
        val surface = surfaces.single()

        surface.onFailed!!("not ready within 6000 ms")
        awaitMain(message = "fallback") { listener.has("loaded:home_banner:ADMOB") }
        assertTrue(surface.destroyed)
        assertFalse(listener.has("impression:home_banner:QARTVELO"))
        assertEquals(0, backend.count("/api/v1/events/impression"))
    }

    @Test
    fun anInlineSlotSizesTheWebViewLikeAnImage() {
        backend.placements = listOf(Placement("home_banner", "banner", timeoutMs = 10_000))
        backend.adResponses.add(backend.fillHtml5("banner", width = 300, height = 250))
        assertTrue(init())
        val banner = showBanner { sizing = BannerSizing.INLINE; inlineMaxHeightDp = 250 }
        surfaces.single().onReady!!()
        awaitMain { listener.has("loaded:home_banner:QARTVELO") }

        val frame = banner.getChildAt(0)
        assertEquals(900, frame.layoutParams.width)
        assertEquals(750, frame.layoutParams.height)
    }

    @Test
    fun aHiddenHostPausesTheAdAndARefreshDestroysTheOldOne() {
        backend.placements = listOf(Placement("home_banner", "banner", timeoutMs = 10_000, refreshSeconds = 30))
        backend.adResponses.add(backend.fillHtml5("banner"))
        assertTrue(init())
        val banner = showBanner()
        val first = surfaces.single()
        first.onReady!!()
        awaitMain { listener.has("impression") }

        banner.visibility = View.GONE
        assertTrue(first.paused)
        banner.visibility = View.VISIBLE
        assertFalse(first.paused)

        backend.adResponses.add(backend.fillHtml5("banner"))
        advance(31_000)
        awaitMain(message = "second surface") { surfaces.size == 2 && surfaces[1].onReady != null }
        surfaces[1].onReady!!()
        awaitMain { listener.count("loaded:home_banner") == 2 }
        assertTrue("old web view destroyed", first.destroyed)
        assertFalse(surfaces[1].destroyed)
    }

    // ---- interstitials (plan Task 3) -------------------------------------------------------------

    @Test
    fun anInterstitialCountsItsImpressionWhenReady() {
        backend.adResponses.add(backend.fillHtml5("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val hostActivity = hostActivity().get()
        QartveloAds.showInterstitial(hostActivity, "game_end")
        val adActivity = launchedAdActivity(hostActivity)
        val surface = surfaces.single()

        settle()
        assertFalse(listener.has("impression"))
        surface.onReady!!()
        awaitMain(message = "impression") { listener.has("impression:game_end:QARTVELO") }

        adActivity.pause()
        assertTrue(surface.paused)
        adActivity.resume()
        assertFalse(surface.paused)

        surface.onClick!!()
        awaitMain(message = "click") { backend.count("/api/v1/events/click") == 1 }
        adActivity.pause().stop().destroy()
        assertTrue("finishing destroys the web view", surface.destroyed || !adActivity.get().isFinishing)
    }

    @Test
    fun anInterstitialThatIsNotReadyInTimeFailsTheShow() {
        backend.adResponses.add(backend.fillHtml5("interstitial"))
        assertTrue(init())
        loadAndWait(AdFormat.INTERSTITIAL, "game_end")
        val hostActivity = hostActivity().get()
        QartveloAds.showInterstitial(hostActivity, "game_end")
        val adActivity = launchedAdActivity(hostActivity)
        val surface = surfaces.single()

        surface.onFailed!!("not ready within 6000 ms")
        awaitMain(message = "show failure") { listener.has("noAd:game_end") }
        assertTrue(adActivity.get().isFinishing)
        assertTrue(surface.destroyed)
        assertFalse(listener.has("impression"))
        assertEquals(0, backend.count("/api/v1/events/impression"))
    }
}
