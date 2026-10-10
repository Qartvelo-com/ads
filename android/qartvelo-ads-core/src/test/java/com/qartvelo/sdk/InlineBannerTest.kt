package com.qartvelo.sdk

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.qartvelo.sdk.fallback.FallbackAdapter
import com.qartvelo.sdk.fallback.FallbackBanner
import com.qartvelo.sdk.fallback.FallbackBannerCallback
import com.qartvelo.sdk.fallback.InlineBannerFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** A 400 x 900 dp phone at 3x; the server answers with a 300 x 250 creative. */
@Config(qualifiers = "w400dp-h900dp-xxhdpi")
class InlineBannerTest : SdkTest() {
    private lateinit var host: ActivityController<Activity>
    private lateinit var container: FrameLayout

    @Before
    fun setUpHost() {
        backend.placements = listOf(Placement("home_banner", "banner", timeoutMs = 10_000))
        backend.adResponses.add(backend.fill("banner", creativePath = "/creatives/b.png", width = 300, height = 250))
        host = hostActivity()
        container = FrameLayout(host.get())
        host.get().setContentView(container)
        // Robolectric leaves the window "not app-visible"; drive the framework's path (see BannerLifecycleTest).
        val decor = host.get().window.decorView
        val root = android.view.View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(decor)!!
        root.javaClass.getDeclaredMethod("dispatchAppVisibility", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(root, true)
        settle(20)
    }

    private fun showBanner(noFill: Boolean = false, configure: QartveloAdsBannerView.() -> Unit): QartveloAdsBannerView {
        if (noFill) backend.adResponses.clear()
        val banner = QartveloAdsBannerView(host.get()).apply {
            placementId = "home_banner"
            listener = this@InlineBannerTest.listener
            configure()
            container.addView(this)
        }
        banner.load()
        val source = if (noFill) "ADMOB" else "QARTVELO"
        awaitMain(message = "banner loaded") { listener.has("loaded:home_banner:$source") }
        settle()
        return banner
    }

    private fun slot(banner: QartveloAdsBannerView): ViewGroup.LayoutParams = banner.getChildAt(0).layoutParams

    private fun request() = backend.bodies("/api/v1/ads/request").single()

    @Test
    fun inlineRequestsSendTheModeAndMaxHeightInPixels() {
        assertTrue(init())
        showBanner { sizing = BannerSizing.INLINE; inlineMaxHeightDp = 250 }
        val body = request()
        assertEquals("inline", body.getString("banner_mode"))
        assertEquals(750, body.getInt("banner_max_height"))
        assertEquals(1200, body.getInt("screen_width"))
        assertFalse(body.has("banner_height"))
    }

    @Test
    fun aTinyOrNegativeMaxHeightIsClampedTo32() {
        assertTrue(init())
        showBanner { sizing = BannerSizing.INLINE; inlineMaxHeightDp = -5 }
        assertEquals(96, request().getInt("banner_max_height"))
    }

    @Test
    fun theSlotTakesTheFittedSizeOfTheServedAd() {
        assertTrue(init())
        val banner = showBanner { sizing = BannerSizing.INLINE; inlineMaxHeightDp = 250 }
        // 1200 px wide, 300x250 creative, max 750 px: 900 x 750 px.
        assertEquals(900, slot(banner).width)
        assertEquals(750, slot(banner).height)
    }

    @Test
    fun aWidthChangeRefitsTheInlineSlotWithoutANewRequest() {
        assertTrue(init())
        val banner = showBanner { sizing = BannerSizing.INLINE; inlineMaxHeightDp = 100 }

        container.layoutParams = FrameLayout.LayoutParams(600, ViewGroup.LayoutParams.MATCH_PARENT)
        settle()

        // 300x250 into 600 x 300 px: 360 x 300.
        assertEquals(360, slot(banner).width)
        assertEquals(300, slot(banner).height)
        assertEquals(1, backend.count("/api/v1/ads/request"))
    }

    @Test
    fun aViewSwitchedToInlineKeepsItsAdUntilTheNextRefresh() {
        backend.placements = listOf(Placement("home_banner", "banner", timeoutMs = 10_000, refreshSeconds = 30))
        assertTrue(init())
        val banner = showBanner { }
        assertTrue(backend.bodies("/api/v1/ads/request").single().has("banner_height"))

        banner.destroy()
        banner.sizing = BannerSizing.INLINE
        banner.load()
        settle()
        assertEquals("no request before the refresh is due", 1, backend.count("/api/v1/ads/request"))

        backend.adResponses.add(backend.fill("banner", creativePath = "/creatives/b.png", width = 300, height = 250))
        advance(31_000)
        awaitMain(message = "refresh") { backend.count("/api/v1/ads/request") == 2 }
        assertEquals("inline", backend.bodies("/api/v1/ads/request")[1].getString("banner_mode"))
    }

    @Test
    fun aHugeMaxHeightIsCappedAtTheApiLimit() {
        assertTrue(init())
        showBanner { sizing = BannerSizing.INLINE; inlineMaxHeightDp = 100_000 }
        assertEquals(20_000, request().getInt("banner_max_height"))
    }

    @Test
    fun anchoredBannersAreUnchanged() {
        assertTrue(init())
        showBanner { }
        val body = request()
        assertFalse(body.has("banner_mode"))
        assertFalse(body.has("banner_max_height"))
        assertTrue(body.has("banner_height"))
    }

    @Test
    fun inlineFallbackUsesTheAdaptersInlineBannerWhenItHasOne() {
        val adapter = InlineAdapter()
        assertTrue(init(adapter = adapter))
        showBanner(noFill = true) { sizing = BannerSizing.INLINE; inlineMaxHeightDp = 250 }
        assertEquals(listOf("inline 400x250"), adapter.bannerCalls)
    }

    @Test
    fun anAdapterWithoutInlineSupportGetsTheAnchoredBanner() {
        val adapter = AnchoredOnlyAdapter()
        assertTrue(init(adapter = adapter))
        val banner = showBanner(noFill = true) { sizing = BannerSizing.INLINE; inlineMaxHeightDp = 250 }
        assertEquals(listOf("anchored 400"), adapter.bannerCalls)
        assertEquals(1, banner.childCount)
    }

    private open class AnchoredOnlyAdapter(private val inner: FakeAdapter = FakeAdapter()) : FallbackAdapter by inner {
        val bannerCalls: MutableList<String> = CopyOnWriteArrayList()

        override fun createBanner(context: Context, placementId: String, adUnitId: String, widthDp: Int, callback: FallbackBannerCallback): FallbackBanner {
            bannerCalls.add("anchored $widthDp")
            return inner.createBanner(context, placementId, adUnitId, widthDp, callback)
        }

        fun inlineBanner(context: Context, placementId: String, adUnitId: String, widthDp: Int, maxHeightDp: Int, callback: FallbackBannerCallback): FallbackBanner {
            bannerCalls.add("inline ${widthDp}x$maxHeightDp")
            return inner.createBanner(context, placementId, adUnitId, widthDp, callback)
        }
    }

    private class InlineAdapter : AnchoredOnlyAdapter(), InlineBannerFallback {
        override fun createInlineBanner(context: Context, placementId: String, adUnitId: String, widthDp: Int, maxHeightDp: Int, callback: FallbackBannerCallback): FallbackBanner =
            inlineBanner(context, placementId, adUnitId, widthDp, maxHeightDp, callback)
    }
}
