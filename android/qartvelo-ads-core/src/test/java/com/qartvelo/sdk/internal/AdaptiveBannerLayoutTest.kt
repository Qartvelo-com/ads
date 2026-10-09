package com.qartvelo.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBannerLayoutTest {
    @Test
    fun followsTheAnchoredAdaptiveFormulaLikeIos() {
        assertEquals("411 dp phone: 411 x 50 / 320", 64, AdaptiveBannerLayout.heightDp(widthDp = 411, screenHeightDp = 923))
        assertEquals("never below 50 dp", 50, AdaptiveBannerLayout.heightDp(widthDp = 300, screenHeightDp = 923))
        assertEquals("never above 90 dp", 90, AdaptiveBannerLayout.heightDp(widthDp = 800, screenHeightDp = 1280))
        assertEquals("at most 15% of the screen height", 60, AdaptiveBannerLayout.heightDp(widthDp = 411, screenHeightDp = 400))
        assertEquals("unknown screen height", 64, AdaptiveBannerLayout.heightDp(widthDp = 411, screenHeightDp = 0))
    }

    @Test
    fun theAdaptersHeightWinsWhenItIsAnAnchoredBannerHeight() {
        assertEquals(70, AdaptiveBannerLayout.heightDp(widthDp = 411, screenHeightDp = 923, preferredHeightDp = 70))
        assertEquals("out of range: formula", 64, AdaptiveBannerLayout.heightDp(widthDp = 411, screenHeightDp = 923, preferredHeightDp = 128))
        assertEquals("no answer: formula", 64, AdaptiveBannerLayout.heightDp(widthDp = 411, screenHeightDp = 923, preferredHeightDp = 0))
    }
}
