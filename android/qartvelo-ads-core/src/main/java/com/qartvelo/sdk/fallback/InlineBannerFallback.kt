package com.qartvelo.sdk.fallback

import android.content.Context

/**
 * Optional companion to [FallbackAdapter]: an inline banner (inside scrolling content) [widthDp]
 * wide and up to [maxHeightDp] tall. Adapters without it get [FallbackAdapter.createBanner]. A
 * separate interface, so adapters built against an older core keep working.
 */
public interface InlineBannerFallback {
    public fun createInlineBanner(
        context: Context,
        placementId: String,
        adUnitId: String,
        widthDp: Int,
        maxHeightDp: Int,
        callback: FallbackBannerCallback,
    ): FallbackBanner
}
