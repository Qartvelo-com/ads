package com.qartvelo.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class InlineBannerFitTest {
    @Test
    fun scalesToTheTighterOfWidthAndMaxHeightLikeTheServer() {
        assertEquals(InlineBannerFit.Size(1200, 187), InlineBannerFit.size(320, 50, 1200, 750))
        assertEquals(InlineBannerFit.Size(900, 750), InlineBannerFit.size(300, 250, 1200, 750))
        assertEquals(InlineBannerFit.Size(360, 300), InlineBannerFit.size(300, 250, 1200, 300))
    }

    @Test
    fun anEmptySlotOrCreativeFitsNothing() {
        assertEquals(InlineBannerFit.Size(0, 0), InlineBannerFit.size(0, 50, 1200, 750))
        assertEquals(InlineBannerFit.Size(0, 0), InlineBannerFit.size(320, 50, 1200, 0))
    }
}
