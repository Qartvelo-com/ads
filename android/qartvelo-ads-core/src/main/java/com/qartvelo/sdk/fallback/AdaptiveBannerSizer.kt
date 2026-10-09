package com.qartvelo.sdk.fallback

import android.content.Context

/**
 * Optional companion to [FallbackAdapter]: the height in dp of the network's anchored adaptive banner
 * at [widthDp]. QartveloAds banners reserve the same slot, so switching to the fallback never changes
 * the layout. A separate interface, not a [FallbackAdapter] method, so adapters built against an older
 * core keep working.
 */
public interface AdaptiveBannerSizer {
    public fun adaptiveBannerHeightDp(context: Context, widthDp: Int): Int
}
