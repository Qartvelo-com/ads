package com.qartvelo.sdk

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.qartvelo.sdk.fallback.AdaptiveBannerSizer
import com.qartvelo.sdk.fallback.FallbackAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/** A 411 x 923 dp phone at 3x: Google's anchored adaptive banner is 64 dp (192 px) tall there. */
@Config(qualifiers = "w411dp-h923dp-xxhdpi")
class AdaptiveBannerTest : SdkTest() {
    private lateinit var host: ActivityController<Activity>
    private lateinit var container: FrameLayout

    @Before
    fun setUpHost() {
        backend.placements = listOf(Placement("home_banner", "banner", timeoutMs = 10_000))
        backend.adResponses.add(backend.fill("banner", creativePath = "/creatives/b.png", width = 320, height = 50))
        host = hostActivity()
        container = FrameLayout(host.get())
        host.get().setContentView(container)
    }

    private fun showBanner(configure: QartveloAdsBannerView.() -> Unit = {}): QartveloAdsBannerView {
        val banner = QartveloAdsBannerView(host.get()).apply {
            placementId = "home_banner"
            listener = this@AdaptiveBannerTest.listener
            configure()
            container.addView(this)
        }
        banner.load()
        awaitMain(message = "banner loaded") { listener.has("loaded:home_banner:QARTVELO") }
        settle()
        return banner
    }

    private fun slot(banner: QartveloAdsBannerView): ViewGroup.LayoutParams = banner.getChildAt(0).layoutParams

    private fun request() = backend.bodies("/api/v1/ads/request").single()

    @Test
    fun reservesTheAnchoredAdaptiveSlotAndAsksForAFittingCreative() {
        assertTrue(init())
        val banner = showBanner()

        assertEquals("full width", ViewGroup.LayoutParams.MATCH_PARENT, slot(banner).width)
        assertEquals("64 dp, like the AdMob fallback, not the creative's 50 dp", 192, slot(banner).height)
        assertEquals(192, request().getInt("banner_height"))
        assertEquals("the slot width in px", 1233, request().getInt("screen_width"))
    }

    @Test
    fun usesTheFallbackNetworksAnchoredHeight() {
        assertTrue(init(adapter = SizingAdapter { _, _ -> 70 }))
        assertEquals(210, slot(showBanner()).height)
        assertEquals(210, request().getInt("banner_height"))
    }

    @Test
    fun ignoresAnAdapterHeightThatIsNotAnAnchoredBanner() {
        assertTrue(init(adapter = SizingAdapter { _, _ -> 128 }))
        assertEquals(192, slot(showBanner()).height)
    }

    @Test
    fun aThrowingAdapterFallsBackToTheFormula() {
        assertTrue(init(adapter = SizingAdapter { _, _ -> throw IllegalStateException("no display") }))
        assertEquals(192, slot(showBanner()).height)
    }

    @Test
    fun aWidthChangeRefitsTheSlotWithoutANewRequest() {
        assertTrue(init())
        val banner = showBanner()

        container.layoutParams = FrameLayout.LayoutParams(900, ViewGroup.LayoutParams.MATCH_PARENT)
        settle()

        assertEquals("300 dp wide: the 50 dp minimum", 150, slot(banner).height)
        assertEquals(1, backend.count("/api/v1/ads/request"))
    }

    @Test
    fun inlineBannersKeepTheCreativeSize() {
        assertTrue(init())
        val banner = showBanner { usesAdaptiveSize = false }

        assertEquals("320 x 50 dp at 3x", 960, slot(banner).width)
        assertEquals(150, slot(banner).height)
        assertFalse(request().has("banner_height"))
    }

    private class SizingAdapter(
        inner: FakeAdapter = FakeAdapter(),
        private val heightDp: (Context, Int) -> Int,
    ) : FallbackAdapter by inner, AdaptiveBannerSizer {
        override fun adaptiveBannerHeightDp(context: Context, widthDp: Int): Int = heightDp(context, widthDp)
    }
}
