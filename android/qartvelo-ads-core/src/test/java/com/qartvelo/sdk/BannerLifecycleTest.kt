package com.qartvelo.sdk

import android.app.Activity
import android.content.Intent
import android.content.MutableContextWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

class BannerLifecycleTest : SdkTest() {
    private lateinit var host: ActivityController<Activity>
    private lateinit var container: FrameLayout

    @Before
    fun setUpHost() {
        // A long request timeout: advancing the fake clock past a refresh must not also fire the
        // main-thread request timeout before the (real-time) HTTP call completes.
        backend.placements = listOf(
            Placement("home_banner", "banner", refreshSeconds = 60, timeoutMs = 10_000),
            Placement("game_end", "interstitial"),
        )
        host = hostActivity()
        container = FrameLayout(host.get())
        host.get().setContentView(container)
        setWindowVisible(true)
    }

    private fun bannerFill() = backend.fill("banner", creativePath = "/creatives/b.png")

    private fun newBanner(l: QartveloAdsListener): QartveloAdsBannerView = QartveloAdsBannerView(host.get()).apply {
        placementId = "home_banner"
        listener = l
        container.addView(this)
    }

    /**
     * Robolectric leaves the window "not app-visible" (GONE). Drive the framework's own path that the
     * window manager uses when an Activity becomes visible (started) or hidden (stopped).
     */
    private fun setWindowVisible(visible: Boolean) {
        val decor = host.get().window.decorView
        val root = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(decor)!!
        root.javaClass.getDeclaredMethod("dispatchAppVisibility", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(root, visible)
        settle(20)
    }

    @Test
    fun adBadgeOpensQartveloSiteWithRefAndIsNotAClick() {
        backend.adResponses.add(bannerFill())
        assertTrue(init())
        val banner = newBanner(listener)
        banner.load()
        awaitMain(message = "banner impression") { listener.has("impression") }

        banner.findByDescription("Ad. About Qartvelo Ads")!!.performClick()
        val opened: Intent = shadowOf(host.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("${backend.baseUrl}?ref=${host.get().packageName}", opened.dataString)

        settle()
        assertEquals(0, backend.count("/api/v1/events/click"))
        assertFalse(listener.has("clicked"))
    }

    @Test
    fun loadsRendersReusesAndRefreshesOnlyWhenDue() {
        backend.adResponses.add(bannerFill())
        assertTrue(init())
        val first = newBanner(listener)
        first.load()
        first.load() // idempotent
        awaitMain(message = "banner impression") { listener.has("impression") }
        assertEquals(listOf("loaded:home_banner:QARTVELO", "shown:home_banner:QARTVELO", "impression:home_banner:QARTVELO"), listener.events)
        assertTrue(first.findByDescription("Ad") is ImageView)
        awaitMain { backend.count("/api/v1/events/impression") == 1 }
        assertEquals("banner", backend.bodies("/api/v1/ads/request").single().getString("format"))

        // Re-created view (rotation / RN re-render): same banner, no new request, no new impression.
        first.destroy()
        container.removeView(first)
        val secondListener = RecordingListener("second")
        val second = newBanner(secondListener)
        second.load()
        awaitMain { secondListener.has("loaded:home_banner:QARTVELO") }
        settle()
        assertEquals(1, backend.count("/api/v1/ads/request"))
        assertEquals(1, backend.count("/api/v1/events/impression"))
        assertTrue(second.findByDescription("Ad") is ImageView)
        assertEquals("global observers are not re-notified on re-attach", 1, global.count("loaded:home_banner:QARTVELO"))

        // Refresh never happens before banner_refresh_seconds.
        advance(55_000)
        settle()
        assertEquals(1, backend.count("/api/v1/ads/request"))
        advance(6_000)
        awaitMain(message = "refresh request") { backend.count("/api/v1/ads/request") == 2 }
        settle()
        // The refresh had no fill and there is no adapter: the current banner simply stays.
        assertTrue(second.findByDescription("Ad") is ImageView)
        assertFalse(secondListener.has("noAd"))

        // Hidden views do not refresh; becoming visible again refreshes once it is due.
        second.visibility = View.GONE
        advance(180_000)
        settle()
        assertEquals(2, backend.count("/api/v1/ads/request"))
        second.visibility = View.VISIBLE
        awaitMain(message = "refresh after visible") { backend.count("/api/v1/ads/request") == 3 }

        // A stopped Activity (window hidden) does not refresh.
        setWindowVisible(false)
        advance(180_000)
        settle()
        assertEquals(3, backend.count("/api/v1/ads/request"))
        setWindowVisible(true)
        awaitMain(message = "refresh after window visible") { backend.count("/api/v1/ads/request") == 4 }

        // Detached views do not refresh either.
        container.removeView(second)
        advance(180_000)
        settle()
        assertEquals(4, backend.count("/api/v1/ads/request"))
        assertEquals(0, listener.offMainThread + secondListener.offMainThread)
    }

    @Test
    fun fallbackBannerLoadedAfterItsHostDetachedDoesNotTakeTheActivityContext() {
        val adapter = FakeAdapter().apply { holdBanners = true }
        assertTrue(init(adapter = adapter))
        val view = newBanner(listener)
        view.load()
        awaitMain(message = "fallback banner requested") { adapter.banners.isNotEmpty() }
        val banner = adapter.banners.single()
        val wrapper = banner.context as MutableContextWrapper

        // The host leaves its window (e.g. the Activity is destroyed) before AdMob answers.
        container.removeView(view)
        adapter.heldBannerLoads.forEach { it() }
        awaitMain(message = "fallback banner loaded") { listener.has("loaded:home_banner:ADMOB") }
        assertSame(app, wrapper.baseContext)
        assertEquals(0, view.childCount)

        // Back in a window, the cached banner renders with the live host's context.
        container.addView(view)
        settle()
        assertSame(banner.view, view.getChildAt(0))
        assertSame(host.get(), wrapper.baseContext)
    }

    @Test
    fun noFillRendersAdMobBannerWithoutLeakingTheActivity() {
        val adapter = FakeAdapter()
        assertTrue(init(adapter = adapter))
        val view = newBanner(listener)
        view.load()
        awaitMain(message = "fallback banner") { listener.has("loaded") || listener.has("loadFailed") }
        assertEquals(listOf("fallbackStarted:home_banner:no_fill", "loaded:home_banner:ADMOB"), listener.events)
        val banner = adapter.banners.single()
        assertEquals("ca-app-pub-test/home_banner", banner.adUnitId)
        assertSame(banner.view, view.getChildAt(0))
        val wrapper = banner.context as MutableContextWrapper
        assertSame(host.get(), wrapper.baseContext)

        // Refresh with no fill keeps the self-refreshing AdMob banner instead of creating another.
        advance(61_000)
        awaitMain { backend.count("/api/v1/ads/request") == 2 }
        settle()
        assertEquals(1, adapter.banners.size)

        // Destroying the Activity detaches the view: the banner no longer references it.
        host.pause().stop().destroy()
        settle()
        assertSame(app, wrapper.baseContext)
        assertTrue(banner.view.parent == null)
        assertTrue("paused while not visible", banner.paused)
    }

    @Test
    fun ourAdsFillReplacesAdMobBannerOnRefresh() {
        val adapter = FakeAdapter()
        assertTrue(init(adapter = adapter))
        val view = newBanner(listener)
        view.load()
        awaitMain { listener.has("loaded:home_banner:ADMOB") }
        backend.adResponses.add(bannerFill())
        advance(61_000)
        awaitMain(message = "QartveloAds banner after refresh") { listener.has("loaded:home_banner:QARTVELO") }
        awaitMain { listener.has("impression:home_banner:QARTVELO") }
        assertTrue(adapter.banners.single().destroyed)
        assertTrue(view.findByDescription("Ad") is ImageView)
    }

    @Test
    fun bannerErrorsAreReported() {
        val unset = QartveloAdsBannerView(host.get()).apply { listener = this@BannerLifecycleTest.listener }
        unset.load()
        awaitMain { listener.has("loadFailed") }
        assertEquals(QartveloAdsErrorCode.INVALID_PLACEMENT, listener.errors.single().code)

        val early = RecordingListener("early")
        newBanner(early).load()
        awaitMain { early.has("loadFailed") }
        assertEquals(QartveloAdsErrorCode.NOT_INITIALIZED, early.errors.single().code)

        assertTrue(init())
        val mismatch = RecordingListener("mismatch")
        QartveloAdsBannerView(host.get()).apply {
            placementId = "game_end"
            listener = mismatch
            container.addView(this)
        }.load()
        awaitMain { mismatch.has("loadFailed") }
        assertEquals(QartveloAdsErrorCode.INVALID_PLACEMENT, mismatch.errors.single().code)

        val noFill = RecordingListener("noFill")
        newBanner(noFill).load()
        awaitMain { noFill.has("loadFailed") }
        assertEquals(listOf("noAd:home_banner", "loadFailed:home_banner:NO_FILL"), noFill.events)
    }
}
