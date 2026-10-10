package com.qartvelo.sdk.internal

/**
 * Height of the compact anchored banner slot, the same rules as iOS: the fallback network's own
 * anchored adaptive height when it reports one between 50 and 90 dp, otherwise width x 50 / 320,
 * kept between 50 and 90 dp and at most 15% of the screen height.
 */
internal object AdaptiveBannerLayout {
    private const val MIN_HEIGHT_DP = 50
    private const val MAX_HEIGHT_DP = 90

    fun heightDp(widthDp: Int, screenHeightDp: Int, preferredHeightDp: Int? = null): Int {
        if (preferredHeightDp != null && preferredHeightDp in MIN_HEIGHT_DP..MAX_HEIGHT_DP) return preferredHeightDp
        val screenCap = if (screenHeightDp > 0) screenHeightDp * 15 / 100 else MAX_HEIGHT_DP
        return maxOf(MIN_HEIGHT_DP, minOf(MAX_HEIGHT_DP, screenCap, widthDp.coerceAtLeast(1) * 50 / 320))
    }
}

/**
 * A banner request's slot in px; [heightPx] is null when the host uses the creative's own size;
 * [inlineMaxHeightPx] is set for inline slots (the ad takes its fitted size, see InlineBannerFit).
 */
internal data class BannerSlot(val widthPx: Int, val heightPx: Int?, val inlineMaxHeightPx: Int? = null)
